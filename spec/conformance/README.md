# Conformance files

Files for checking an implementation of the formats against the specification: `akm/` for maps, `akp/` for palettes.

- `valid/` holds files a reader must accept. Beside each is a `.txt` with what it decodes to, one fact per line.
  Coordinates are in grid units; an image is given by its length and the SHA-256 of its bytes. A file of version
  2.0 is the one encoding of its content, so writing the decoded content again must give the same bytes.
  `later-minor-version` is the exception: it is what a later minor version might write, and a version 2.0 reader
  accepts it and keeps the field and the chunk it does not know.
- `invalid/` holds files a reader must refuse, each breaking one rule. Beside each is a `.txt` with the reason, one
  of the names in the library's `FormatError`. The file's name says which rule it breaks.

The library's own tests read these files (`ConformanceTest` in `hondius-akm` and `hondius-akp`), so they cannot
drift from what the reference implementation does. They are rewritten from the cases in those tests by running

```sh
HONDIUS_WRITE_CONFORMANCE=1 ./gradlew :hondius-akm:jvmTest :hondius-akp:jvmTest
```

and the change they show in a diff is then the change to the format.
