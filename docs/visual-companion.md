# Visual Companion

Minikun can return a visual gallery for image-search requests while retrieving normal web evidence for the answer in parallel.

## Response attachment contract

Image attachments include a local `url` for display, the remote `original_url` for follow-up vision input, `source_url`, `description`, `origin`, and optional provider, license, and dimensions. `origin` is one of `web`, `generated`, or `user`; the Cockpit always shows that label instead of presenting searched images as generated work.

The gallery supports a lightbox, broken-image fallback, source links, “ask from this image”, “create from this reference”, and one-click saving to a persistent inspiration board. Follow-up actions attach the original remote image to the next OpenAI-compatible multimodal request.

## Safety and storage

`GET /v1/images/proxy?url=…` accepts HTTPS images only, rejects local/private targets, does not follow redirects, allows JPEG/PNG/WebP, enforces byte and timeout limits, and serves a bounded TTL cache with `nosniff` and an ETag. Inspiration boards store image/source URLs and user-provided labels, not image bytes.

Configuration uses the `minikun.visual.proxy.*` properties in `application.properties`. Board APIs live under `/v1/personal/inspiration-boards` and use the same optional personal-management token as other personal APIs.
