// Builds dist/lbp-resource.js: one plain script with pako, cwlib.js (TeaVM's
// output, target/javascript/cwlib.js) and js/lbp-resource.js.
// Usage: node scripts/bundle.mjs [cwlib.js] [out.js]
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cwlibPath = process.argv[2] || path.join(root, "target/javascript/cwlib.js");
const outPath = process.argv[3] || path.join(root, "dist/lbp-resource.js");
const read = (p) => fs.readFileSync(p, "utf8");

const pako = read(path.join(root, "js/vendor/pako.min.js"));
const cwlib = read(cwlibPath);
const api = read(path.join(root, "js/lbp-resource.js"))
  .replace(/\nif \(typeof module === "object"[^\n]*\n?$/, "\n");
const version = JSON.parse(read(path.join(root, "package.json"))).version;

const out = `/*! lbp-resource.js ${version} | decompress_resource, compress_resource, lbp_file_to_json, json_to_lbp_file
 * https://github.com/Kalkoen31/lbp-resource-js
 * Contains: cwlib (c) ennuo, MIT, https://github.com/ennuo/toolkit, compiled to JavaScript with TeaVM
 * (Apache-2.0, includes jzlib, BSD) together with parts of Gson (Apache-2.0) and JOML (MIT);
 * pako (c) Vitaly Puzrin and Andrei Tuputcyn, MIT and zlib licenses.
 * License texts: https://github.com/Kalkoen31/lbp-resource-js/tree/main/licenses
 */
(function (root) {
var LBPResourcePako = (function () {
  var module = { exports: {} }, exports = module.exports, define;
${pako}
  return module.exports;
})();
var LBPResourceCwlib = (function () {
  var exports = {}, module, define;
${cwlib}
  return exports;
})();
${api}
var api = createLBPResource(LBPResourcePako, LBPResourceCwlib);
if (typeof module === "object" && module && module.exports) module.exports = api;
else {
  root.LBPResource = api;
  ["decompress_resource", "compress_resource", "lbp_file_to_json", "json_to_lbp_file"].forEach(function (name) { root[name] = api[name]; });
}
})(typeof globalThis !== "undefined" ? globalThis : typeof self !== "undefined" ? self : this);
`;
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, out);
console.log("wrote " + path.relative(process.cwd(), outPath) + " (" + Math.round(out.length / 1024) + " KB)");
