#!/usr/bin/env python3
"""Owner-only headed browser, controlled over private stdio rather than a debugging port."""
import asyncio
import ipaddress
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
from urllib.parse import urlsplit


def public_url(url):
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Only public HTTP/HTTPS URLs without credentials are supported")
    addresses = socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(address[4][0]).is_global for address in addresses):
        raise ValueError("Private/local network targets are blocked")
    return parsed.hostname.lower()


async def proxy_connection(reader, writer):
    """Pin each upstream socket to a public IP; DNS rebinding must not reach the host LAN."""
    upstream = None
    try:
        header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
        method, target, version = header.split(b"\r\n", 1)[0].decode("ascii").split(" ")
        url = "https://" + target if method == "CONNECT" else target
        parsed = urlsplit(url)
        await asyncio.to_thread(public_url, url)
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        addresses = await asyncio.to_thread(socket.getaddrinfo, parsed.hostname, port, 0, socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(address[4][0]).is_global for address in addresses):
            raise ValueError("Private destination")
        # Connect to the validated numeric IP, never resolve the hostname a second time during connect.
        for address in addresses[:4]:
            try:
                remote_reader, upstream = await asyncio.wait_for(asyncio.open_connection(address[4][0], port), 3)
                break
            except (OSError, TimeoutError):
                continue
        if upstream is None:
            raise OSError("No reachable public address")
        if method == "CONNECT":
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await writer.drain()
        else:
            path = parsed.path or "/"
            if parsed.query:
                path += "?" + parsed.query
            lines = [line for line in header.split(b"\r\n")[1:] if line and not line.lower().startswith(
                (b"connection:", b"proxy-connection:", b"proxy-authorization:"))]
            upstream.write(f"{method} {path} {version}\r\n".encode("ascii")
                           + b"\r\n".join(lines) + b"\r\nConnection: close\r\n\r\n")
            await upstream.drain()

        async def relay(source, destination):
            while data := await source.read(65_536):
                destination.write(data)
                await destination.drain()

        tasks = [asyncio.create_task(relay(reader, upstream)), asyncio.create_task(relay(remote_reader, writer))]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
    except Exception:
        if upstream is None:
            writer.write(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            try:
                await writer.drain()
            except ConnectionError:
                pass
    finally:
        if upstream is not None:
            upstream.close()
        writer.close()


# Convert links to readable source URLs; retain tables/list text and omit page chrome.
EXTRACT = """() => {
  const root = document.querySelector('main, article, [role="main"]') || document.body;
  const copy = root.cloneNode(true);
  copy.querySelectorAll('script,style,noscript,nav,footer,header,form').forEach(el => el.remove());
  copy.querySelectorAll('a[href]').forEach(el => {
    try { const url = new URL(el.getAttribute('href'), document.baseURI);
      if (['http:', 'https:'].includes(url.protocol)) el.append(` (${url.href})`);
    } catch (_) {}
  });
  const container = document.createElement('div');
  container.style.cssText = 'position:absolute;left:-100000px;top:0;pointer-events:none';
  container.append(copy); document.body.append(container);
  const content = container.innerText; container.remove();
  return {url: location.href, title: document.title, content};
}"""


async def snapshot(page, selector=""):
    await asyncio.to_thread(public_url, page.url)
    controls = await page.locator("button, a[href], input:not([type=password]), textarea, select, [role=status], [aria-live], output, h1, h2, p[id]").evaluate_all("""elements => {
      const selector = el => {
        if(el.id && document.querySelectorAll('#'+CSS.escape(el.id)).length===1) return '#'+CSS.escape(el.id);
        const parts=[]; let current=el;
        while(current && current.tagName!=='BODY') {
          const tag=current.tagName.toLowerCase();
          const siblings=[...current.parentElement.children].filter(other=>other.tagName===current.tagName);
          parts.unshift(tag+':nth-of-type('+(siblings.indexOf(current)+1)+')'); current=current.parentElement;
        }
        return 'body > '+parts.join(' > ');
      };
      return elements.filter(el=>el.getClientRects().length).slice(0,100).map(el=>({tag:el.tagName.toLowerCase(),id:el.id,name:el.getAttribute('name')||'',
        type:el.getAttribute('type')||'',label:(el.innerText||el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.getAttribute('name')||el.tagName).slice(0,200),
        href:el.getAttribute('href')||'',formAction:el.form?.getAttribute('action')||'',disabled:el.disabled||false,selector:selector(el)}));
    }""")
    text = (await page.locator("body").inner_text())[:12000]
    selected_text = ""
    selected_value = ""
    if selector:
        target = page.locator(selector)
        if await target.count() != 1:
            raise ValueError("Selector must match exactly one element")
        if not await target.is_visible():
            raise ValueError("Hidden elements cannot be inspected")
        if await target.get_attribute("type") == "password":
            raise ValueError("Password fields stay in manual login")
        selected_text = (await target.inner_text())[:2000]
        if await target.evaluate("el => ['INPUT','TEXTAREA','SELECT'].includes(el.tagName)"):
            selected_value = (await target.input_value())[:2000]
    result = {"success": True, "url": page.url, "title": await page.title(), "text": text, "controls": controls,
              "selectedText": selected_text, "selectedValue": selected_value}
    result["state"] = hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest()
    return result


async def interact(page, request):
    # Compare the live page against the exact preview immediately before the interaction.
    url = request.get("url", "")
    if request.get("action") != "snapshot" and page.url.split("#")[0] != url.split("#")[0]:
        raise ValueError("Page URL changed after preview")
    selector = request.get("selector", "")
    action = request.get("action")
    before = await snapshot(page, selector)
    if action == "snapshot":
        return before
    if request.get("expected_state") != before["state"]:
        raise ValueError("Page changed after preview")
    target = page.locator(selector)
    if not await target.is_visible():
        raise ValueError("Target is not visible")
    if action == "click":
        await target.click()
    elif action == "fill":
        await target.fill(str(request.get("value", "")))
    elif action == "select":
        await target.select_option(str(request.get("value", "")))
    else:
        raise ValueError("Unsupported browser interaction")
    # A click acknowledgement is not evidence that a form submission succeeded.
    result = await snapshot(page)
    result["interaction"] = action
    return result


async def serve(profile):
    os.environ.setdefault("PLAYWRIGHT_BROWSERS_PATH", str(Path(__file__).resolve().parent / "browsers"))
    from playwright.async_api import async_playwright
    os.umask(0o077)
    profile.mkdir(parents=True, exist_ok=True, mode=0o700)
    profile.chmod(0o700)
    state_file = profile / "session-state.json"
    proxy = await asyncio.start_server(proxy_connection, "127.0.0.1", 0, limit=32_768)
    async with proxy, async_playwright() as playwright:
        context = await playwright.chromium.launch_persistent_context(
            str(profile), headless=False, accept_downloads=False, service_workers="block", chromium_sandbox=True,
            proxy={"server": f"http://127.0.0.1:{proxy.sockets[0].getsockname()[1]}"},
            # Chromium normally bypasses proxies for loopback; force those attempts through the public-IP guard too.
            args=["--proxy-bypass-list=<-loopback>", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"],
        )
        context.set_default_timeout(5_000)
        context.set_default_navigation_timeout(20_000)
        if state_file.exists():
            state = json.loads(state_file.read_text())
            await context.add_cookies(state.get("cookies", []))

        async def guard(route):
            try:
                await asyncio.to_thread(public_url, route.request.url)
                await route.continue_()
            except (ValueError, OSError):
                await route.abort()

        await context.route("**/*", guard)
        # WebSocket handshakes do not pass through ordinary HTTP routing.
        await context.route_web_socket("**/*", lambda websocket: websocket.close())
        pages = {}
        requested_urls = {}
        status_codes = {}
        try:
            while line := await asyncio.to_thread(sys.stdin.readline):
                try:
                    request = json.loads(line)
                    action = request.get("action")
                    url = request.get("url", "")
                    host = await asyncio.to_thread(public_url, url)
                    if action == "open":
                        if host not in pages and len(pages) >= 16:
                            raise ValueError("Close the browser session before opening more than 16 domains")
                        page = pages.get(host)
                        if page is None or page.is_closed():
                            page = await context.new_page()
                            pages[host] = page
                            def record_status(response, host=host, page=page):
                                if response.request.is_navigation_request() and response.frame == page.main_frame:
                                    status_codes[host] = response.status
                            page.on("response", record_status)
                        await page.goto(url, wait_until="domcontentloaded")
                        requested_urls[host] = url.split('#')[0]
                        await page.bring_to_front()
                        actual_host = await asyncio.to_thread(public_url, page.url)
                        pages[actual_host] = page
                        requested_urls[actual_host] = page.url.split('#')[0]
                        status_codes[actual_host] = status_codes.get(host, 200)
                        result = {"success": True, "host": host, "url": page.url}
                    elif action in ("snapshot", "click", "fill", "select"):
                        page = pages.get(host)
                        if page is None or page.is_closed():
                            raise ValueError("Open this domain first")
                        result = await interact(page, request)
                        actual_host = await asyncio.to_thread(public_url, page.url)
                        pages[actual_host] = page
                        requested_urls[actual_host] = page.url.split('#')[0]
                        status_codes[actual_host] = status_codes.get(host, 200)
                    elif action == "render":
                        page = pages.get(host)
                        if page is None or page.is_closed():
                            raise ValueError("Open this domain in the browser session first")
                        if requested_urls.get(host) != url.split('#')[0]:
                            await page.goto(url, wait_until="domcontentloaded")
                            requested_urls[host] = url.split('#')[0]
                        await page.wait_for_selector("body", state="visible", timeout=3_000)
                        # ponytail: bounded settling detects ordinary delayed DOM updates, not perpetual/live streams.
                        previous = ""
                        stable = 0
                        for _ in range(8):
                            current = await page.locator("body").inner_text()
                            stable = stable + 1 if current == previous else 0
                            previous = current
                            if stable >= 3:
                                break
                            await asyncio.sleep(0.3)
                        await asyncio.to_thread(public_url, page.url)
                        result = await page.evaluate(EXTRACT)
                        result["content"] = result["title"] + "\n" + result["content"]
                        result["success"] = True
                        result["status_code"] = status_codes.get(host, 200)
                        await context.storage_state(path=str(state_file))
                    else:
                        raise ValueError("Unknown browser action")
                except Exception as exception:
                    # Do not export cookies, browser internals, page scripts, or secrets in error text.
                    result = {"success": False, "error": "Browser operation failed: " + type(exception).__name__}
                print(json.dumps(result, ensure_ascii=False), flush=True)
        finally:
            try:
                await context.storage_state(path=str(state_file))
            finally:
                await context.close()


if __name__ == "__main__":
    try:
        asyncio.run(serve(Path(sys.argv[1]).expanduser().resolve()))
    except Exception as exception:
        print(json.dumps({"success": False, "error": "Browser runtime unavailable: " + type(exception).__name__}), flush=True)
        sys.exit(1)
