# lbp-resource-js

Four functions for LittleBigPlanet resource files, in plain JavaScript (no
WebAssembly, no Java runtime):

| Function | Returns |
| --- | --- |
| `decompress_resource(file)` | `[data, info]`: the decompressed payload and the header info |
| `compress_resource(data, info)` | the resource file |
| `lbp_file_to_json(file)` | the resource as JSON, like the toolkit's *Export > JSON* |
| `json_to_lbp_file(json)` | the resource file, like the toolkit's *New > JSON* |

All four are synchronous. `file` and `data` are binary strings (one character
per byte, 0 to 255, as `atob()` or `FileReader.readAsBinaryString()` give you)
or `Uint8Array` / `ArrayBuffer`.

Try it: open [`index.html`](index.html) in a browser (it works from a local
folder too) and pick a resource file.

## Using it

One file, [`dist/lbp-resource.js`](dist/lbp-resource.js) (about 1.1 MB, 370 KB
gzipped), with nothing else to load:

```html
<script src="lbp-resource.js"></script>
<script>
  const [data, info] = decompress_resource(file);
  const json = lbp_file_to_json(file);
  json.resource.traction = 3;             // e.g. a MATERIAL
  const edited = json_to_lbp_file(json);
</script>
```

The functions are globals and also on `window.LBPResource`. In Node,
`const api = require("./dist/lbp-resource.js")`.

### decompress_resource / compress_resource

```js
const [data, info] = decompress_resource(file);
// data: the payload, zlib chunks inflated (a string for string input, a Uint8Array for bytes)
// info:
{
  "magic": "504c4e62",              // first 4 bytes as hex: "PLNb"
  "resource_type": "PLAN",
  "revision": 1016,                 // header revision (0x3f8)
  "branch": [3, 4],                 // [branch id, branch revision], [0, 0] if not stored
  "compressed": true,               // payload stored as zlib chunks
  "compression_flag_integers": true,
  "compression_flag_matricies": true,
  "compression_flag_vectors": true,
  "dependencies": [
    { "resource_type": "MESH", "hash": null, "guid": 12 },
    { "resource_type": "SKELETON_MAP", "hash": "5ad8be5e446ddedb0c90b247f1c01a605cc2f2b0", "guid": null }
  ]
}

const again = compress_resource(data, info); // same bytes as `file`
```

- `compress_resource(data, info)` writes the header, zlib chunks (32 KB each,
  level 9) and dependency table the way cwlib's `SerializedResource.compress`
  does. `compress_resource(...decompress_resource(file))` gives back the same
  bytes for files cwlib or this script wrote (the tests check this against
  files cwlib made). Files compressed by something else, like the games
  themselves, may come out with different zlib bytes that hold the same data.
- Only what the revision stores is written: branch from 0x271, compression
  flags from 0x297 (or 0x272 on the Leerdammer branch), the dependency table
  from 0x109. Set `"compressed": false` to store the payload as is.
- `resource_type` in a dependency can be a name from cwlib's `ResourceType` or
  its number. `magic` can be left out if `resource_type` is given (a binary
  resource is assumed).
- Text resources (`…t`) give `info` with only `magic` and `resource_type`.
  Textures (`TEX `, `GTF `, `GTFs`, `GTFS`) add `texture_header` (hex) for
  GTF headers. Alear extra data after the dependency table comes back as
  `info.alear` with `version`, `branch_id`, `branch_revision`, `compressed`,
  the three flags and `data`.
- Not supported: encrypted resources (`…e`) and static meshes (`SMHb`). Both
  throw an error saying so.

These two are hand-written JavaScript, in [`js/lbp-resource.js`](js/lbp-resource.js),
and use [pako](https://github.com/nodeca/pako) for zlib.

### lbp_file_to_json / json_to_lbp_file

```js
const json = lbp_file_to_json(file);
// { "revision": 1016, "type": "PLAN", "resource": { "things": [...], ... } }
const file2 = json_to_lbp_file(json);
```

The JSON is the toolkit's: `{ "revision", "branch": { "id", "revision" },
"alear": {…}, "type", "resource" }` (branch and alear only when set). It covers
the resource types cwlib can convert: plans, levels, meshes, materials, gfx
materials, palettes, profiles, slot lists, scripts, packs, DLC lists, bevels,
instruments, outfit lists, streaming chunks and the rest of the types with a
cwlib class. Others throw an error saying so.

- `lbp_file_to_json(file, { text: true })` returns the JSON text exactly as the
  toolkit writes it (pretty printed), for saving as a `.json` file.
- The toolkit's JSON can hold `NaN`, `Infinity` and 64-bit numbers that
  JavaScript can't hold exactly. In the object these are strings (`"NaN"`,
  `"12345678901234567890"`); `json_to_lbp_file` and the toolkit read them back.
- `json_to_lbp_file` takes the object or JSON text (also `.json` files the
  toolkit exported). It returns a binary string, or a `Uint8Array` with
  `{ bytes: true }`. It builds the file like the toolkit does: compressed, with
  the revision's default compression flags. The dependency table can come out
  in a different order than in the original file, as in the toolkit.

#### How the JSON functions work

The resource formats are defined by cwlib, ennuo's Java library behind the
[Craftworld Toolkit](https://github.com/ennuo/toolkit): about 60,000 lines
describing every resource, thing and part across the game versions. Rather
than rewrite that by hand, this project compiles cwlib from Java to plain
JavaScript with [TeaVM](https://teavm.org/). The output is generated code
(minified), not hand-written, but it is ordinary JavaScript.

cwlib's JSON goes through Gson, which needs Java reflection that TeaVM doesn't
have. [`MiniGson`](src/main/java/lbpjs/json/MiniGson.java) replaces the part of
Gson that cwlib uses. It follows Gson 2.8.9's rules with cwlib's settings and
uses cwlib's own JSON serializers unchanged. A test runs real Gson and
MiniGson side by side on the JVM, on made-up resources, and checks that the
JSON text and the rebuilt files match.

The rest of cwlib compiles as is from upstream, pinned as the `upstream/`
submodule. TeaVM gets a few stand-ins for things a web page doesn't have: the
toolkit's open archives and GUI state (`ResourceSystem`), and JOML's
`sun.misc.Unsafe` and `Math.fma` paths. See [`src/main/java/lbpjs`](src/main/java/lbpjs).

## Building

Needs JDK 17 or later, Maven and Node 18 or later.

```sh
git clone --recursive https://github.com/Kalkoen31/lbp-resource-js
cd lbp-resource-js
./build.sh
```

`build.sh` installs cwlib from `upstream/` into the local Maven repository,
runs the Java tests (which also write test fixtures to `target/fixtures`),
compiles to `target/javascript/cwlib.js` with TeaVM, bundles
`dist/lbp-resource.js` and runs the JavaScript tests. The GitHub Actions
workflow does the same on every push.

## Tests

Only made-up resources are used, no game files.

- `ParityTest` (Java): resources of 20 types at five game revisions (LBP1,
  Leerdammer, LBP2, Vita, LBP3), filled with random values, including plans and
  levels with things carrying random parts, and meshes. For each, the toolkit's
  JSON from real Gson must equal MiniGson's text, and building a file from it
  must give the same bytes.
- `ContainerFixturesTest` (Java): files written by cwlib's `SerializedResource`
  for the header and compression cases.
- `test/lbp-resource.test.mjs` (Node): the bundled functions against all of
  the above, so the compiled JavaScript has to give the same JSON text as the
  JVM and build the same files.

## Credits and licenses

- cwlib and the Craftworld Toolkit by [ennuo](https://github.com/ennuo/toolkit), MIT.
- Compiled with TeaVM (Apache-2.0). The output includes TeaVM's class library,
  parts of Gson (Apache-2.0) and JOML (MIT).
- pako (MIT and zlib licenses).

This project is MIT licensed, see [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
