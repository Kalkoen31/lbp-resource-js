// Tests for dist/lbp-resource.js, with made-up resources only (no game data).
// Usage: node --test test/   (after the Maven build and scripts/bundle.mjs)
//
// The fixtures come from the Maven tests (target/fixtures, or FIXTURES=<dir>):
//   containers/  files cwlib's SerializedResource wrote, for decompress/compress
//   *.bin, *.gson.json, *.rebuilt.bin  resources of every JSON-capable type at
//     several game revisions, with the toolkit's JSON (real Gson on the JVM) and
//     the file the toolkit builds back from it, for lbp_file_to_json/json_to_lbp_file.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const api = require("../dist/lbp-resource.js");
const { decompress_resource, compress_resource, toBinaryString, toBytes, parseGsonJson } = api;

const ascii = (s) => new Uint8Array([...s].map((c) => c.charCodeAt(0)));
const words = (n, seed = 1) => {
  const parts = ["sackboy ", "\u0018U\u0005\u0001", "popit ", "\0\0\0\0", "craftworld "];
  let s = "", x = seed;
  while (s.length < n) { x = (x * 1103515245 + 12345) >>> 0; s += parts[x % parts.length]; }
  return s.slice(0, n);
};
const noise = (n, seed = 7) => {
  const b = new Uint8Array(n); let x = seed;
  for (let i = 0; i < n; i++) { x = (x * 1664525 + 1013904223) >>> 0; b[i] = x >>> 24; }
  return b;
};

// The example from the request, at a revision where every field is stored.
const sample = {
  magic: "504c4e62",
  revision: 0x3f8,
  branch: [3, 4],
  compression_flag_integers: true, compression_flag_matricies: true, compression_flag_vectors: true,
  dependencies: [
    { resource_type: "MESH", hash: null, guid: 12 },
    { resource_type: "SKELETON_MAP", hash: "5ad8be5e446ddedb0c90b247f1c01a605cc2f2b0", guid: null },
  ],
};

test("request example round-trips: compress then decompress", () => {
  const data = "\u0018U\u0005\u0001";
  const file = compress_resource(data, sample);
  assert.equal(typeof file, "string");
  assert.equal(file.slice(0, 4), "PLNb");
  const [out, info] = decompress_resource(file);
  assert.equal(out, data);
  assert.deepEqual(info, { ...sample, resource_type: "PLAN", compressed: true });
  assert.equal(compress_resource(out, info), file, "compress(decompress(file)) is byte for byte the same");
});

test("header layout matches cwlib for a 0x3f8 resource", () => {
  const file = toBytes(compress_resource("", { ...sample, compressed: false, dependencies: [] }));
  // magic, revision, dependency table offset, branch id, branch revision, flags, compressed, (no data), dependency count
  assert.deepEqual([...file], [...ascii("PLNb"), 0, 0, 3, 0xf8, 0, 0, 0, 0x12, 0, 3, 0, 4, 7, 0, 0, 0, 0, 0]);
});

test("Uint8Array and ArrayBuffer in, Uint8Array out", () => {
  const data = toBytes(words(3000));
  const file = compress_resource(data, sample);
  assert.ok(file instanceof Uint8Array);
  const [out, info] = decompress_resource(file.buffer);
  assert.ok(out instanceof Uint8Array);
  assert.deepEqual(out, data);
  assert.equal(info.compressed, true);
  assert.equal(compress_resource(toBinaryString(data), sample), toBinaryString(file));
});

test("multi-chunk, empty, uncompressed and incompressible payloads", () => {
  for (const [name, data, extra] of [
    ["3 chunks", toBytes(words(0x8000 * 2 + 77)), {}],
    ["exactly one chunk", toBytes(words(0x8000)), {}],
    ["empty", new Uint8Array(0), {}],
    ["uncompressed", toBytes(words(500)), { compressed: false }],
    ["noise", noise(5000), {}],
  ]) {
    const file = compress_resource(data, { ...sample, ...extra });
    const [out, info] = decompress_resource(file);
    assert.deepEqual(out, data, name);
    assert.deepEqual(compress_resource(out, info), file, name);
  }
});

test("old revisions only store what they have", () => {
  const data = words(700);
  // 0x272 (LBP1): branch fields, no compression flags.
  let [, info] = decompress_resource(compress_resource(data, { ...sample, revision: 0x272, branch: [0, 0] }));
  assert.equal(info.compression_flag_integers, false);
  // Leerdammer branch of 0x272 stores flags from branch revision 2.
  [, info] = decompress_resource(compress_resource(data, { ...sample, revision: 0x272, branch: [0x4c44, 2] }));
  assert.equal(info.compression_flag_integers, true);
  assert.deepEqual(info.branch, [0x4c44, 2]);
  // 0x200: no branch in the header.
  [, info] = decompress_resource(compress_resource(data, { ...sample, revision: 0x200 }));
  assert.deepEqual(info.branch, [0, 0]);
  assert.equal(info.dependencies.length, 2);
  // 0x150: zlib stream but no "compressed" byte; uncompressed is a stream with no chunks.
  for (const compressed of [true, false]) {
    const file = compress_resource(data, { ...sample, revision: 0x150, compressed });
    [, info] = decompress_resource(file);
    assert.equal(info.compressed, compressed);
    assert.equal(compress_resource(data, info), file);
  }
  // 0x100: no dependency table.
  const file = compress_resource(data, { ...sample, revision: 0x100, dependencies: [] });
  const [out] = decompress_resource(file);
  assert.equal(out, data);
  assert.throws(() => compress_resource(data, { ...sample, revision: 0x100 }), /no dependency table/);
});

test("text, texture and Alear extra data", () => {
  const text = "some text\n";
  const tfile = compress_resource(text, { magic: "434f4e74" }); // CONt
  assert.equal(tfile, "CONt" + text);
  assert.deepEqual(decompress_resource(tfile), [text, { magic: "434f4e74", resource_type: "GAME_CONSTANTS" }]);

  const pixels = words(40000, 3);
  const tex = compress_resource(pixels, { magic: "54455820" }); // "TEX "
  assert.deepEqual(decompress_resource(tex), [pixels, { magic: "54455820", resource_type: "TEXTURE", compressed: true }]);
  const gtfHeader = "86010200aae4000080008000000102000000020000000000";
  const gtf = compress_resource(pixels, { magic: "47544620", texture_header: gtfHeader });
  const [gout, ginfo] = decompress_resource(gtf);
  assert.equal(gout, pixels);
  assert.equal(ginfo.texture_header, gtfHeader);
  assert.equal(compress_resource(gout, ginfo), gtf);

  const alear = { version: 1, branch_id: 0x414c5352, branch_revision: 3, compressed: true,
    compression_flag_integers: true, compression_flag_matricies: false, compression_flag_vectors: false, data: words(300, 9) };
  const afile = compress_resource(words(800), { ...sample, alear });
  const [, ainfo] = decompress_resource(afile);
  assert.deepEqual(ainfo.alear, alear);
  assert.equal(compress_resource(words(800), ainfo), afile);
});

test("clear errors", () => {
  assert.throws(() => decompress_resource("NOPEabcd"), /unknown magic/);
  assert.throws(() => decompress_resource("PLN"), /too short/);
  assert.throws(() => decompress_resource("PLNb\0\0\x03\xf8"), /truncated/);
  assert.throws(() => decompress_resource("PLNe\0\0\x03\xf8"), /Encrypted/);
  assert.throws(() => decompress_resource("PLNbĀ"), /binary string/);
  assert.throws(() => compress_resource("x", { ...sample, dependencies: [{ resource_type: "NOPE", guid: 1 }] }), /resource_type/);
  assert.throws(() => compress_resource("x", { ...sample, dependencies: [{ resource_type: "MESH", hash: "abc" }] }), /hash/);
  assert.throws(() => compress_resource("x"), /info object/);
  const file = toBytes(compress_resource(words(2000), sample));
  file[30] ^= 0xff;
  assert.throws(() => decompress_resource(file), /zlib/);
});

test("Gson JSON with NaN, Infinity and big longs parses", () => {
  const out = parseGsonJson('{"a": NaN, "b": -Infinity, "c": [1.5, -2, 1e3], "d": "NaN 12", "e": 12345678901234567890, "f": "q\\"NaN"}');
  assert.deepEqual(out, { a: "NaN", b: "-Infinity", c: [1.5, -2, 1000], d: "NaN 12", e: "12345678901234567890", f: 'q"NaN' });
});

const fixturesRoot = process.env.FIXTURES || path.join(path.dirname(new URL(import.meta.url).pathname), "../target/fixtures");
const haveFixtures = fs.existsSync(path.join(fixturesRoot, "index.json"));
const skip = !haveFixtures && "no fixtures: run the Maven build first, or set FIXTURES";
const sortDeps = (d) => [...d].sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));

test("matches cwlib on files cwlib wrote", { skip }, () => {
  const fixtures = path.join(fixturesRoot, "containers");
  const cases = JSON.parse(fs.readFileSync(path.join(fixtures, "containers.json"), "utf8"));
  assert.ok(cases.length >= 10);
  for (const c of cases) {
    const file = new Uint8Array(fs.readFileSync(path.join(fixtures, c.name + ".bin")));
    const expected = new Uint8Array(fs.readFileSync(path.join(fixtures, c.name + ".data")));
    const [data, info] = decompress_resource(file);
    assert.deepEqual(data, expected, c.name + ": payload");
    assert.equal(info.resource_type, c.resource_type, c.name);
    if (c.revision !== undefined) {
      for (const k of ["revision", "branch", "compression_flag_integers", "compression_flag_matricies", "compression_flag_vectors"])
        assert.deepEqual(info[k], c[k], c.name + ": " + k);
      assert.deepEqual(sortDeps(info.dependencies), sortDeps(c.dependencies), c.name + ": dependencies");
      if (c.alear_branch_id !== undefined) {
        assert.equal(info.alear.branch_id, c.alear_branch_id, c.name);
        assert.equal(info.alear.branch_revision, c.alear_branch_revision, c.name);
      }
    }
    // pako at level 9 produces the same zlib streams as java.util.zip at BEST_COMPRESSION here.
    assert.deepEqual(compress_resource(data, info), file, c.name + ": compress(decompress(file)) differs from cwlib's file");
  }
});

// ---- JSON functions --------------------------------------------------------------

const { lbp_file_to_json, json_to_lbp_file } = api;
const readCase = (name, ext) => fs.readFileSync(path.join(fixturesRoot, name + ext));

// Same resource, allowing for the dependency table's order (cwlib keeps the
// dependencies in a HashSet, whose order isn't fixed).
function assertSameResource(actual, expected, message) {
  const [a, ai] = decompress_resource(actual);
  const [b, bi] = decompress_resource(expected);
  assert.equal(a, b, message + ": payload");
  assert.deepEqual({ ...ai, dependencies: sortDeps(ai.dependencies) }, { ...bi, dependencies: sortDeps(bi.dependencies) }, message + ": header");
}

test("lbp_file_to_json / json_to_lbp_file match the toolkit on every fixture", { skip }, () => {
  const cases = JSON.parse(fs.readFileSync(path.join(fixturesRoot, "index.json"), "utf8"));
  assert.ok(cases.length > 200, "fixtures: " + cases.length);
  const types = new Set();
  for (const c of cases) {
    const file = readCase(c.name, ".bin").toString("latin1");
    const gson = readCase(c.name, ".gson.json").toString("utf8");
    types.add(c.type);

    // Text output is byte for byte what the toolkit's Export > JSON writes.
    assert.equal(lbp_file_to_json(file, { text: true }), gson, c.name + ": JSON text");
    // The object is that JSON, with NaN/Infinity/huge integers as strings.
    const json = lbp_file_to_json(file);
    assert.deepEqual(json, parseGsonJson(gson), c.name + ": JSON object");
    assert.equal(json.type, c.type);
    // Bytes in work the same.
    assert.deepEqual(lbp_file_to_json(new Uint8Array(readCase(c.name, ".bin"))), json, c.name + ": Uint8Array input");

    if (!c.rebuilds) continue;
    const rebuilt = readCase(c.name, ".rebuilt.bin").toString("latin1");
    assertSameResource(json_to_lbp_file(json), rebuilt, c.name + ": from object");
    assertSameResource(json_to_lbp_file(JSON.stringify(json)), rebuilt, c.name + ": from JSON.stringify text");
    assertSameResource(json_to_lbp_file(gson), rebuilt, c.name + ": from the toolkit's JSON text");
    const bytes = json_to_lbp_file(json, { bytes: true });
    assert.ok(bytes instanceof Uint8Array);
    assert.equal(toBinaryString(bytes), json_to_lbp_file(json));
  }
  assert.ok(types.size >= 20, "types: " + [...types]);
});

test("JSON round trip through the request's functions", { skip }, () => {
  const file = readCase("things-plan-lbp2-1", ".bin").toString("latin1");
  const json = lbp_file_to_json(file);
  assert.equal(json.type, "PLAN");
  assert.ok(Array.isArray(json.resource.things) && json.resource.things.length > 0);
  // Edit a value, build, read back.
  json.resource.things[0].UID = 4242;
  const edited = json_to_lbp_file(json);
  assert.equal(lbp_file_to_json(edited).resource.things[0].UID, 4242);
  // And the header functions see a normal plan.
  const [, info] = decompress_resource(edited);
  assert.equal(info.resource_type, "PLAN");
  assert.equal(info.revision, json.revision);
});

test("JSON functions give clear errors", () => {
  assert.throws(() => lbp_file_to_json("NOPEabcd"), /lbp_file_to_json failed: .*binary/);
  assert.throws(() => lbp_file_to_json(compress_resource("hello", { magic: "434f4e74" })), /Only binary resources/);
  assert.throws(() => lbp_file_to_json("PLNbĀ"), /binary string/);
  assert.throws(() => json_to_lbp_file({ revision: 1016 }), /missing "type"/);
  assert.throws(() => json_to_lbp_file({ revision: 1016, type: "NOPE", resource: {} }), /Unknown resource type "NOPE"/);
  assert.throws(() => json_to_lbp_file("{"), /json_to_lbp_file failed/);
  assert.throws(() => json_to_lbp_file(42), /needs the JSON object/);
});
