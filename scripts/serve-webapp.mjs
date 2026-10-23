#!/usr/bin/env node
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("..", import.meta.url)), "app", "src", "main", "assets", "www");
const PORT = Number(process.env.PORT || 4173);
const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json",
  ".wasm": "application/wasm",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".png": "image/png",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
};

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    let pathname = decodeURIComponent(url.pathname);
    if (pathname.startsWith("/api/")) {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: { code: "not_found", message: "Offline" } }));
      return;
    }
    let file = normalize(join(ROOT, pathname));
    if (!file.startsWith(ROOT)) throw new Error("forbidden");
    try {
      if (!(await stat(file)).isFile()) throw new Error("dir");
    } catch {
      file = join(ROOT, "index.html");
    }
    const data = await readFile(file);
    res.writeHead(200, {
      "Content-Type": MIME[extname(file).toLowerCase()] || "application/octet-stream",
      "Cache-Control": "no-store",
    });
    res.end(data);
  } catch {
    res.writeHead(404).end("Not found");
  }
}).listen(PORT, () => console.log(`Doory offline preview: http://localhost:${PORT}/`));