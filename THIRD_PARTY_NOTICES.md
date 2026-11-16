# Third-party notices

`dist/lbp-resource.js` contains code from these projects. Their license texts
are in [`licenses/`](licenses/).

| Project | License | Where it comes from | License text |
| --- | --- | --- | --- |
| [cwlib / Craftworld Toolkit](https://github.com/ennuo/toolkit), Copyright (c) 2024 ennuo | MIT | compiled from `upstream/lib/cwlib` | [cwlib-MIT.txt](licenses/cwlib-MIT.txt) |
| [TeaVM](https://github.com/konsoletyper/teavm) class library and runtime, Copyright Alexey Andreev | Apache License 2.0 | added by the TeaVM compiler | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| [JZlib](https://github.com/ymnk/jzlib), Copyright (c) 2011 ymnk, JCraft, Inc. (inside TeaVM's class library) | BSD 3-clause | zlib used by cwlib's code | [JZlib-BSD.txt](licenses/JZlib-BSD.txt) |
| [Gson](https://github.com/google/gson) 2.8.9, Copyright Google Inc. (JSON tree, parser and writer) | Apache License 2.0 | dependency of cwlib | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| [JOML](https://github.com/JOML-CI/JOML) 1.10.5, Copyright (c) 2015-2022 JOML | MIT | dependency of cwlib | [JOML-MIT.txt](licenses/JOML-MIT.txt) |
| [pako](https://github.com/nodeca/pako) 2.1.0, Copyright (C) 2014-2017 Vitaly Puzrin and Andrei Tuputcyn | MIT and zlib | `js/vendor/pako.min.js` | [pako-LICENSE.txt](licenses/pako-LICENSE.txt) |

Changes, as the Apache License asks: the TeaVM and Gson code is compiled to
JavaScript and minified, and only the parts cwlib uses are included.
`src/main/java/lbpjs/json/MiniGson.java` is new code written for this project
that follows the behaviour of Gson's object binding (`ReflectiveTypeAdapterFactory`
and related adapters) so that cwlib's JSON comes out the same; no Gson source
was copied into it. cwlib itself is compiled unchanged.
