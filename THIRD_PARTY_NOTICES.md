# Third-party notices

`dist/lbp-resource.js` contains code from these projects.

| Project | License | Where |
| --- | --- | --- |
| [cwlib / Craftworld Toolkit](https://github.com/ennuo/toolkit) by ennuo | MIT | compiled from `upstream/lib/cwlib` (see `upstream/LICENSE`) |
| [TeaVM](https://github.com/konsoletyper/teavm) class library and runtime | Apache License 2.0 | compiled in by TeaVM |
| [JZlib](https://github.com/ymnk/jzlib) (inside TeaVM's class library) | BSD 3-clause | zlib used by cwlib's code |
| [Gson](https://github.com/google/gson) 2.8.9 (JSON tree, parser and writer) | Apache License 2.0 | dependency of cwlib |
| [JOML](https://github.com/JOML-CI/JOML) 1.10.5 | MIT | dependency of cwlib |
| [pako](https://github.com/nodeca/pako) 2.x | MIT and zlib | `js/vendor/pako.min.js`, see `js/vendor/pako-LICENSE.txt` |

The Apache License 2.0 text is at https://www.apache.org/licenses/LICENSE-2.0.

`src/main/java/lbpjs/json/MiniGson.java` is new code that follows the behaviour
of Gson's object binding (`ReflectiveTypeAdapterFactory` and related adapters).
