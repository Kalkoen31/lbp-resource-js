/*
 * LittleBigPlanet resource functions for web pages, in plain JavaScript.
 *
 *   decompress_resource(file)       -> [data, info]
 *   compress_resource(data, info)   -> file
 *   lbp_file_to_json(file)          -> object  (the toolkit's "Export > JSON")
 *   json_to_lbp_file(json)          -> file    (the toolkit's "New > JSON")
 *
 * "file" and "data" are binary strings (one char per byte, 0-255, like atob()
 * returns) or Uint8Array/ArrayBuffer. See README.md for the info object.
 *
 * This file is the readable part. The build (scripts/bundle.mjs) puts it in
 * dist/lbp-resource.js together with pako (zlib) and cwlib.js, which is cwlib
 * (github.com/ennuo/toolkit, MIT) compiled from Java to JavaScript by TeaVM.
 * decompress_resource/compress_resource are written here by hand and only need
 * pako; the JSON functions call into cwlib.js, since they need all of cwlib's
 * resource definitions.
 */
function createLBPResource(pako, cwlib) {
  "use strict";

  // cwlib.enums.ResourceType: [name, magic or null, type value]
  const RESOURCE_TYPES = [
    ["INVALID", null, 0], ["TEXTURE", "TEX", 1], ["GTF_TEXTURE", "GTF", 1], ["MESH", "MSH", 2],
    ["PIXEL_SHADER", null, 3], ["VERTEX_SHADER", null, 4], ["ANIMATION", "ANM", 5],
    ["GUID_SUBSTITUTION", "GSB", 6], ["GFX_MATERIAL", "GMT", 7], ["SPU_ELF", null, 8],
    ["LEVEL", "LVL", 9], ["FILENAME", null, 10], ["SCRIPT", "FSH", 11],
    ["SETTINGS_CHARACTER", "CHA", 12], ["FILE_OF_BYTES", null, 13], ["SETTINGS_SOFT_PHYS", "SSP", 14],
    ["FONTFACE", "FNT", 15], ["MATERIAL", "MAT", 16], ["DOWNLOADABLE_CONTENT", "DLC", 17],
    ["EDITOR_SETTINGS", null, 18], ["JOINT", "JNT", 19], ["GAME_CONSTANTS", "CON", 20],
    ["POPPET_SETTINGS", "POP", 21], ["CACHED_LEVEL_DATA", "CLD", 22], ["SYNCED_PROFILE", "PRF", 23],
    ["BEVEL", "BEV", 24], ["GAME", "GAM", 25], ["SETTINGS_NETWORK", "NWS", 26], ["PACKS", "PCK", 27],
    ["BIG_PROFILE", "BPR", 28], ["SLOT_LIST", "SLT", 29], ["TRANSLATION", null, 30],
    ["ADVENTURE_CREATE_PROFILE", "ADC", 31], ["LOCAL_PROFILE", "IPR", 32], ["LIMITS_SETTINGS", "LMT", 33],
    ["TUTORIALS", "TUT", 34], ["GUID_LIST", "GLT", 35], ["AUDIO_MATERIALS", "AUM", 36],
    ["SETTINGS_FLUID", "SSF", 37], ["PLAN", "PLN", 38], ["TEXTURE_LIST", "TXL", 39],
    ["MUSIC_SETTINGS", "MUS", 40], ["MIXER_SETTINGS", "MIX", 41], ["REPLAY_CONFIG", "REP", 42],
    ["PALETTE", "PAL", 43], ["STATIC_MESH", "SMH", 44], ["ANIMATED_TEXTURE", "ATX", 45],
    ["VOIP_RECORDING", "VOP", 46], ["PINS", "PIN", 47], ["INSTRUMENT", "INS", 48], ["SAMPLE", null, 49],
    ["OUTFIT_LIST", "OUT", 50], ["PAINT_BRUSH", "PBR", 51], ["THING_RECORDING", "REC", 52],
    ["PAINTING", "PTG", 53], ["QUEST", "QST", 54], ["ANIMATION_BANK", "ABK", 55],
    ["ANIMATION_SET", "AST", 56], ["SKELETON_MAP", "SMP", 57], ["SKELETON_REGISTRY", "SRG", 58],
    ["SKELETON_ANIM_STYLES", "SAS", 59], ["CROSSPLAY_VITA", null, 60], ["STREAMING_CHUNK", "CHK", 61],
    ["ADVENTURE_SHARED_DATA", "ADS", 62], ["ADVENTURE_PLAY_PROFILE", "ADP", 63],
    ["ANIMATION_MAP", "AMP", 64], ["CACHED_COSTUME_DATA", "CCD", 65], ["DATA_LABELS", "DLA", 66],
    ["ADVENTURE_MAPS", "ADM", 67], ["BONE_SET", "BST", 128],
  ];
  const TYPE_BY_VALUE = new Map(), TYPE_BY_NAME = new Map(), TYPE_BY_MAGIC = new Map();
  for (const [name, magic, value] of RESOURCE_TYPES) {
    if (!TYPE_BY_VALUE.has(value)) TYPE_BY_VALUE.set(value, name); // TEXTURE wins over GTF_TEXTURE, as in cwlib
    TYPE_BY_NAME.set(name, value);
    if (magic) TYPE_BY_MAGIC.set(magic, name);
  }

  // Revision thresholds used by cwlib's SerializedResource.
  const REV_DEPENDENCIES = 0x109;  // dependency table offset in the header
  const REV_ZLIB = 0x132;          // payload is a chunked zlib stream from here on
  const REV_FLAGS_HEADER = 0x189;  // "is compressed" byte in the header
  const REV_BRANCH = 0x271;        // branch id + branch revision in the header
  const REV_COMPRESSION_FLAGS = 0x297;
  const LEERDAMMER = { head: 0x272, id: 0x4c44, resources: 0x2 }; // has compression flags from branch revision 2
  const CHUNK_SIZE = 0x8000;
  const ZLIB_LEVEL = 9;            // java.util.zip.Deflater.BEST_COMPRESSION, as cwlib uses

  const FLAG_INTEGERS = 1, FLAG_VECTORS = 2, FLAG_MATRICES = 4;

  // ---- bytes and binary strings -------------------------------------------------

  function toBytes(input, what) {
    if (typeof input === "string") {
      const out = new Uint8Array(input.length);
      for (let i = 0; i < input.length; i++) {
        const c = input.charCodeAt(i);
        if (c > 0xff) throw new TypeError(what + " must be a binary string (one char per byte), found U+" + c.toString(16).padStart(4, "0") + " at " + i);
        out[i] = c;
      }
      return out;
    }
    if (input instanceof Uint8Array) return input;
    if (input instanceof ArrayBuffer) return new Uint8Array(input);
    if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    throw new TypeError(what + " must be a binary string, Uint8Array or ArrayBuffer");
  }

  function toBinaryString(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i += 0x8000)
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return s;
  }

  const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  function fromHex(text, what, length) {
    if (typeof text !== "string" || !/^([0-9a-fA-F]{2})*$/.test(text) || (length != null && text.length !== length * 2))
      throw new TypeError(what + " must be " + (length != null ? length + " bytes of " : "") + "hex, got " + JSON.stringify(text));
    const out = new Uint8Array(text.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(text.substr(i * 2, 2), 16);
    return out;
  }

  class Reader {
    constructor(bytes) { this.b = bytes; this.v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); this.o = 0; }
    need(n) { if (this.o + n > this.b.length) throw new RangeError("Resource is truncated: wanted " + n + " bytes at offset " + this.o + " of " + this.b.length); }
    u8() { this.need(1); return this.b[this.o++]; }
    u16() { this.need(2); const x = this.v.getUint16(this.o); this.o += 2; return x; }
    u32() { this.need(4); const x = this.v.getUint32(this.o); this.o += 4; return x; }
    bytes(n) { if (n < 0) throw new RangeError("Bad length " + n + " at offset " + this.o); this.need(n); const x = this.b.subarray(this.o, this.o + n); this.o += n; return x; }
  }

  class Writer {
    constructor() { this.b = new Uint8Array(256); this.n = 0; }
    grow(n) {
      if (this.n + n <= this.b.length) return;
      let size = this.b.length * 2;
      while (size < this.n + n) size *= 2;
      const b = new Uint8Array(size); b.set(this.b.subarray(0, this.n)); this.b = b;
    }
    u8(x) { this.grow(1); this.b[this.n++] = x & 0xff; }
    u16(x) { this.grow(2); this.b[this.n++] = (x >>> 8) & 0xff; this.b[this.n++] = x & 0xff; }
    u32(x) { this.grow(4); for (let s = 24; s >= 0; s -= 8) this.b[this.n++] = (x >>> s) & 0xff; }
    bytes(a) { this.grow(a.length); this.b.set(a, this.n); this.n += a.length; }
    putU32(at, x) { for (let i = 0; i < 4; i++) this.b[at + i] = (x >>> (24 - i * 8)) & 0xff; }
    align(a) { while (this.n % a !== 0) this.u8(0); }
    result() { return this.b.slice(0, this.n); }
  }

  // ---- chunked zlib streams (cwlib.util.Compressor) -----------------------------

  // u16 1, u16 chunk count, (u16 compressed, u16 decompressed) per chunk, then the
  // chunks. A chunk whose two sizes are equal is stored as is. No chunks means the
  // data follows uncompressed up to `end`.
  function readChunks(r, end) {
    r.u16();
    const count = r.u16();
    if (count === 0) return { data: r.bytes(end - r.o), compressed: false };
    const sizes = [];
    let total = 0;
    for (let i = 0; i < count; i++) { const c = r.u16(), d = r.u16(); sizes.push([c, d]); total += d; }
    const out = new Uint8Array(total);
    let at = 0;
    for (const [c, d] of sizes) {
      const chunk = r.bytes(c);
      let plain = chunk;
      if (c !== d) {
        try { plain = pako.inflate(chunk); } catch (e) { throw new Error("Corrupt zlib chunk at offset " + (r.o - c) + ": " + (e.message || e)); }
        if (plain.length !== d) throw new Error("zlib chunk at offset " + (r.o - c) + " inflated to " + plain.length + " bytes, header says " + d);
      }
      out.set(plain, at);
      at += d;
    }
    return { data: out, compressed: true };
  }

  function writeChunks(w, data, compressed) {
    if (!compressed) { w.u32(0); w.bytes(data); return; }
    const chunks = [];
    for (let i = 0; i < data.length; i += CHUNK_SIZE) {
      const plain = data.subarray(i, i + CHUNK_SIZE);
      let packed = pako.deflate(plain, { level: ZLIB_LEVEL });
      // Equal sizes mean "stored" to the reader, so a chunk that doesn't shrink
      // by at least a byte (rare, cwlib doesn't check) is stored instead.
      if (packed.length === plain.length || packed.length > 0xffff) packed = plain;
      chunks.push([packed, plain.length]);
    }
    w.u16(1);
    w.u16(chunks.length);
    for (const [packed, size] of chunks) { w.u16(packed.length); w.u16(size); }
    for (const [packed] of chunks) w.bytes(packed);
  }

  // ---- header helpers --------------------------------------------------------------

  function hasCompressionFlags(head, branchId, branchRevision) {
    return head >= REV_COMPRESSION_FLAGS ||
      (head === LEERDAMMER.head && branchId === LEERDAMMER.id && branchRevision >= LEERDAMMER.resources);
  }

  function flagsToInfo(flags, info) {
    info.compression_flag_integers = (flags & FLAG_INTEGERS) !== 0;
    info.compression_flag_matricies = (flags & FLAG_MATRICES) !== 0;
    info.compression_flag_vectors = (flags & FLAG_VECTORS) !== 0;
    return info;
  }

  function flagsFromInfo(info) {
    const yes = (a, b) => !!(info[a] !== undefined ? info[a] : info[b]);
    return (yes("compression_flag_integers") ? FLAG_INTEGERS : 0) |
      (yes("compression_flag_vectors") ? FLAG_VECTORS : 0) |
      (yes("compression_flag_matricies", "compression_flag_matrices") ? FLAG_MATRICES : 0);
  }

  function typeValue(t) {
    if (typeof t === "number") return t;
    if (TYPE_BY_NAME.has(t)) return TYPE_BY_NAME.get(t);
    if (typeof t === "string" && /^\d+$/.test(t)) return Number(t);
    throw new TypeError("Unknown dependency resource_type " + JSON.stringify(t));
  }

  function checkU16(x, what) {
    if (!Number.isInteger(x) || x < 0 || x > 0xffff) throw new TypeError(what + " must be an integer 0-65535, got " + JSON.stringify(x));
    return x;
  }
  function checkU32(x, what) {
    if (!Number.isInteger(x) || x < 0 || x > 0xffffffff) throw new TypeError(what + " must be an integer 0-4294967295, got " + JSON.stringify(x));
    return x;
  }

  // ---- decompress_resource ---------------------------------------------------------

  /**
   * Splits an LBP resource file into its decompressed payload and header info.
   * @param {string|Uint8Array|ArrayBuffer} file
   * @returns {[string|Uint8Array, object]} payload (same kind as the input) and info
   */
  function decompress_resource(file) {
    const asString = typeof file === "string";
    const bytes = toBytes(file, "Resource");
    if (bytes.length < 4) throw new RangeError("Resource is too short to have a header");
    const out = (b) => (asString ? toBinaryString(b) : b.slice());
    const r = new Reader(bytes);
    const magicBytes = r.bytes(4);
    const magic = String.fromCharCode(...magicBytes);
    const resourceType = TYPE_BY_MAGIC.get(magic.substring(0, 3).toUpperCase());
    if (!resourceType) throw new Error("Not an LBP resource: unknown magic " + JSON.stringify(magic));
    const method = magic[3];
    const info = { magic: hex(magicBytes), resource_type: resourceType };

    if (method === "t") return [out(bytes.subarray(4)), info];

    if (method === " " || method === "s" || method === "S") {
      if (resourceType !== "TEXTURE") info.texture_header = hex(r.bytes(method === "S" ? 0x2c : 0x18));
      const { data, compressed } = readChunks(r, bytes.length);
      info.compressed = compressed;
      return [out(data), info];
    }

    if (method === "e") throw new Error("Encrypted resources (magic ...e) aren't supported by decompress_resource");
    if (method !== "b") throw new Error("Unknown serialization method " + JSON.stringify(method) + " in magic " + JSON.stringify(magic));
    if (resourceType === "STATIC_MESH") throw new Error("Static mesh resources (SMHb) aren't supported by decompress_resource");

    const head = r.u32();
    let depOffset = -1, branchId = 0, branchRevision = 0, flags = 0;
    let compressed = head >= REV_ZLIB;
    if (head >= REV_DEPENDENCIES) {
      depOffset = r.u32();
      if (head >= REV_FLAGS_HEADER) {
        if (head >= REV_BRANCH) { branchId = r.u16(); branchRevision = r.u16(); }
        if (hasCompressionFlags(head, branchId, branchRevision)) flags = r.u8();
        compressed = r.u8() !== 0;
      }
    }

    const end = depOffset >= 0 ? depOffset : bytes.length;
    if (end < r.o || end > bytes.length) throw new RangeError("Dependency table offset " + depOffset + " is outside the file");
    let data;
    if (compressed) {
      // Before the header byte existed, an uncompressed payload is a zlib stream with no chunks.
      const chunks = readChunks(r, end);
      data = chunks.data;
      if (head < REV_FLAGS_HEADER) compressed = chunks.compressed;
    } else data = r.bytes(end - r.o);

    info.revision = head;
    info.branch = [branchId, branchRevision];
    info.compressed = compressed;
    flagsToInfo(flags, info);
    info.dependencies = [];

    let eof = r.o;
    if (depOffset >= 0) {
      r.o = depOffset;
      const count = r.u32();
      for (let i = 0; i < count; i++) {
        const f = r.u8();
        const guid = (f & 2) ? r.u32() : null;
        const hash = (f & 1) ? hex(r.bytes(20)) : null;
        const value = r.u32();
        info.dependencies.push({ resource_type: TYPE_BY_VALUE.get(value) || value, hash, guid });
      }
      eof = r.o;
    }

    // Alear (custom server) extra data, after the dependency table on a 16 byte boundary.
    if (eof % 16 !== 0) eof += 16 - (eof % 16);
    if (eof + 16 <= bytes.length && String.fromCharCode(...bytes.subarray(eof, eof + 4)) === "ALSR") {
      r.o = eof + 4;
      const alear = { version: r.u16() };
      const extraFlags = r.u8();
      const extraCompressed = r.u8() !== 0;
      alear.branch_id = r.u32();
      alear.branch_revision = r.u32();
      alear.compressed = extraCompressed;
      flagsToInfo(extraFlags, alear);
      alear.data = out(extraCompressed ? readChunks(r, bytes.length).data : r.bytes(bytes.length - r.o));
      info.alear = alear;
    }

    return [out(data), info];
  }

  // ---- compress_resource -----------------------------------------------------------

  /**
   * Builds an LBP resource file from a payload and the info decompress_resource returned.
   * @param {string|Uint8Array|ArrayBuffer} data decompressed payload
   * @param {object} params info object
   * @returns {string|Uint8Array} resource file (same kind as `data`)
   */
  function compress_resource(data, params) {
    const asString = typeof data === "string";
    const payload = toBytes(data, "Data");
    if (!params || typeof params !== "object") throw new TypeError("compress_resource needs the info object as its second argument");
    let magicBytes;
    if (params.magic != null) magicBytes = fromHex(params.magic, "magic", 4);
    else {
      const t = RESOURCE_TYPES.find((x) => x[0] === params.resource_type && x[1]);
      if (!t) throw new TypeError("compress_resource needs \"magic\" (or a \"resource_type\" with a magic header)");
      magicBytes = new Uint8Array([...t[1]].map((c) => c.charCodeAt(0)).concat([0x62])); // "b"
    }
    const magic = String.fromCharCode(...magicBytes);
    const resourceType = TYPE_BY_MAGIC.get(magic.substring(0, 3).toUpperCase());
    if (!resourceType) throw new Error("Unknown magic " + JSON.stringify(magic));
    const method = magic[3];
    const w = new Writer();
    w.bytes(magicBytes);
    const done = () => (asString ? toBinaryString(w.result()) : w.result());

    if (method === "t") { w.bytes(payload); return done(); }

    if (method === " " || method === "s" || method === "S") {
      if (resourceType !== "TEXTURE") w.bytes(fromHex(params.texture_header || "", "texture_header", method === "S" ? 0x2c : 0x18));
      writeChunks(w, payload, params.compressed !== false);
      return done();
    }

    if (method === "e") throw new Error("Encrypted resources (magic ...e) aren't supported by compress_resource");
    if (method !== "b") throw new Error("Unknown serialization method " + JSON.stringify(method) + " in magic " + JSON.stringify(magic));
    if (resourceType === "STATIC_MESH") throw new Error("Static mesh resources (SMHb) aren't supported by compress_resource");

    const head = checkU32(params.revision, "revision");
    const branch = params.branch || [0, 0];
    const branchId = checkU16(branch[0], "branch[0]"), branchRevision = checkU16(branch[1], "branch[1]");
    const compressed = head < REV_ZLIB ? false : params.compressed !== false;
    w.u32(head);
    let depOffsetAt = -1;
    if (head >= REV_DEPENDENCIES) {
      depOffsetAt = w.n;
      w.u32(0);
      if (head >= REV_FLAGS_HEADER) {
        if (head >= REV_BRANCH) { w.u16(branchId); w.u16(branchRevision); }
        if (hasCompressionFlags(head, branchId, branchRevision)) w.u8(flagsFromInfo(params));
        w.u8(compressed ? 1 : 0);
      }
    }

    if (head < REV_ZLIB || (head >= REV_FLAGS_HEADER && !compressed)) w.bytes(payload);
    else writeChunks(w, payload, compressed);

    if (depOffsetAt >= 0) {
      w.putU32(depOffsetAt, w.n);
      const deps = params.dependencies || [];
      w.u32(deps.length);
      for (const dep of deps) {
        const guid = dep && dep.guid != null ? checkU32(Number(dep.guid), "dependency guid") : null;
        const hash = dep && dep.hash != null ? fromHex(dep.hash, "dependency hash", 20) : null;
        w.u8((guid !== null ? 2 : 0) | (hash !== null ? 1 : 0));
        if (guid !== null) w.u32(guid);
        if (hash !== null) w.bytes(hash);
        w.u32(dep ? typeValue(dep.resource_type) : 0);
      }

      const alear = params.alear;
      if (alear) {
        const extra = toBytes(alear.data != null ? alear.data : "", "alear.data");
        const extraCompressed = alear.compressed !== false;
        w.align(16);
        w.bytes([0x41, 0x4c, 0x53, 0x52]); // "ALSR"
        w.u16(checkU16(alear.version != null ? alear.version : 1, "alear.version"));
        w.u8(flagsFromInfo(alear));
        w.u8(extraCompressed ? 1 : 0);
        w.u32(checkU32(alear.branch_id || 0, "alear.branch_id"));
        w.u32(checkU32(alear.branch_revision != null ? alear.branch_revision : 1, "alear.branch_revision"));
        if (extraCompressed) writeChunks(w, extra, true);
        else w.bytes(extra);
      }
    } else if (params.dependencies && params.dependencies.length) {
      throw new Error("Revision 0x" + head.toString(16) + " has no dependency table, so dependencies can't be stored (needs 0x109 or later)");
    }

    return done();
  }

  // Gson writes NaN/Infinity, which JSON.parse rejects, and longs that don't fit a
  // double. Both are quoted as strings; Gson reads them back from strings.
  function parseGsonJson(text) {
    let out = "", i = 0;
    const n = text.length;
    while (i < n) {
      const c = text[i];
      if (c === '"') {
        let j = i + 1;
        while (j < n && text[j] !== '"') j += text[j] === "\\" ? 2 : 1;
        out += text.slice(i, j + 1);
        i = j + 1;
        continue;
      }
      const m = /[-\dNI]/.test(c) && !/[\w.]/.test(text[i - 1] || "") &&
        /^(-?Infinity|NaN|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(i, i + 400));
      if (m) {
        const tok = m[1];
        const unsafe = /^-?\d+$/.test(tok) && !Number.isSafeInteger(Number(tok));
        out += (tok === "NaN" || tok.endsWith("Infinity") || unsafe) ? '"' + tok + '"' : tok;
        i += tok.length;
        continue;
      }
      out += c;
      i++;
    }
    return JSON.parse(out);
  }

  // ---- JSON through cwlib (compiled to JavaScript) --------------------------------

  function java(what, call) {
    if (!cwlib) throw new Error(what + " needs cwlib.js (use dist/lbp-resource.js, which includes it)");
    try { return call(); }
    catch (e) {
      const message = e && e.message && e.message !== "Java exception thrown" ? e.message : String(e);
      const error = new Error(what + " failed: " + message);
      error.cause = e;
      throw error;
    }
  }

  /**
   * Converts a resource file to the toolkit's JSON ("Export > JSON"): an object
   * with "revision", "type", "resource" and, for some revisions, "branch".
   * NaN and Infinity, and integers bigger than JavaScript numbers hold exactly,
   * come out as strings ("NaN"); json_to_lbp_file and the toolkit read them back.
   * @param {string|Uint8Array|ArrayBuffer} file
   * @param {{text?: boolean}} [opts] text: true returns the JSON text exactly as
   *   the toolkit writes it (pretty printed, NaN unquoted) instead of an object
   * @returns {object|string}
   */
  function lbp_file_to_json(file, opts) {
    const binary = typeof file === "string" ? (toBytes(file, "Resource"), file) : toBinaryString(toBytes(file, "Resource"));
    if (opts && opts.text) return java("lbp_file_to_json", () => cwlib.resourceToGsonJson(binary));
    return JSON.parse(java("lbp_file_to_json", () => cwlib.resourceToJson(binary)));
  }

  /**
   * Builds a resource file from the toolkit's JSON ("New > JSON"), as an object
   * or as JSON text (for example a .json file the toolkit exported).
   * @param {object|string} json
   * @param {{bytes?: boolean}} [opts] bytes: true returns a Uint8Array
   * @returns {string|Uint8Array} the resource file as a binary string
   */
  function json_to_lbp_file(json, opts) {
    if (json == null || (typeof json !== "string" && typeof json !== "object"))
      throw new TypeError("json_to_lbp_file needs the JSON object (or its text)");
    const text = typeof json === "string" ? json : JSON.stringify(json);
    const binary = java("json_to_lbp_file", () => cwlib.jsonToResource(text));
    return opts && opts.bytes ? toBytes(binary, "Result") : binary;
  }

  return {
    decompress_resource, compress_resource, lbp_file_to_json, json_to_lbp_file,
    toBinaryString, toBytes, parseGsonJson, RESOURCE_TYPES,
  };
}

if (typeof module === "object" && module && module.exports) module.exports = createLBPResource;
