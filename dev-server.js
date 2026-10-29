const http = require("http");
const fs = require("fs");
const path = require("path");
const { handle } = require("./api/shop");

const root = path.join(__dirname, "public");
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
};

http.createServer((req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  if (url.pathname.startsWith("/api/")) {
    req.url = url.pathname + url.search;
    handle(req, res);
    return;
  }
  const file = path.join(root, url.pathname === "/" ? "index.html" : url.pathname);
  if (!file.startsWith(root) || !fs.existsSync(file)) {
    res.statusCode = 404;
    res.end("Not found");
    return;
  }
  res.setHeader("Content-Type", types[path.extname(file)] || "application/octet-stream");
  fs.createReadStream(file).pipe(res);
}).listen(process.env.PORT || 8093);
