"""Offline DOM checks. Run with the installed Minikun browser Python and browser cache."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("action_browser_session", Path(__file__).with_name("session.py"))
session = importlib.util.module_from_spec(spec)
spec.loader.exec_module(session)
try:
    from playwright.async_api import async_playwright
except ImportError:
    async_playwright = None


@unittest.skipIf(async_playwright is None, "Use Minikun browser runtime Python to run DOM tests")
class BrowserInteractionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.patch = patch.object(session, "public_url", return_value="example.test")
        self.patch.start()
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)
        self.page = await self.browser.new_page()
        await self.page.route("**/*", lambda route: route.fulfill(body='''<html><title>Offline form</title><body>
          <label>Name <input id="name"></label><input id="password" type="password">
          <button id="save" onclick="document.querySelector('#result').textContent='Saved'">Save</button>
          <input id="secret" type="hidden" value="private"><p id="result" role="status">Waiting</p></body></html>''', content_type="text/html"))
        await self.page.goto("https://example.test/form")

    async def asyncTearDown(self):
        await self.browser.close()
        await self.playwright.stop()
        self.patch.stop()

    async def test_fill_click_and_independent_read_back(self):
        before = await session.snapshot(self.page, "#name")
        self.assertTrue(any(c["selector"] == "#save" for c in before["controls"]))
        self.assertFalse(any(c["id"] == "password" for c in before["controls"]))
        await session.interact(self.page, {"action": "fill", "url": self.page.url, "selector": "#name", "value": "Minikun", "expected_state": before["state"]})
        after = await session.snapshot(self.page, "#name")
        self.assertEqual("Minikun", after["selectedValue"])
        button = await session.snapshot(self.page, "#save")
        await session.interact(self.page, {"action": "click", "url": self.page.url, "selector": "#save", "expected_state": button["state"]})
        self.assertEqual("Saved", (await session.snapshot(self.page, "#result"))["selectedText"])

    async def test_changed_dom_url_or_password_prevents_interaction(self):
        before = await session.snapshot(self.page, "#save")
        await self.page.locator("#save").evaluate("el => el.textContent='Different action'")
        with self.assertRaises(ValueError):
            await session.interact(self.page, {"action": "click", "url": self.page.url, "selector": "#save", "expected_state": before["state"]})
        self.assertEqual("Waiting", (await session.snapshot(self.page, "#result"))["selectedText"])
        with self.assertRaises(ValueError):
            await session.interact(self.page, {"action": "click", "url": "https://example.test/other", "selector": "#save", "expected_state": before["state"]})
        with self.assertRaises(ValueError):
            await session.snapshot(self.page, "#password")
        with self.assertRaises(ValueError):
            await session.snapshot(self.page, "#secret")
        with self.assertRaises(ValueError):
            await session.snapshot(self.page, "input")


if __name__ == "__main__":
    unittest.main()
