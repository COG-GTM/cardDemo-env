# CardDemo Mainframe Estate

This repository is the legacy COBOL/JCL source side of the CardDemo
migration demonstration. The runnable transfer-fee batch chain is built and
executed with:

```text
make up
make build
make run CHAIN=xferfee
```

See `docs/CARDDEMO-README.md` for the upstream application documentation and
the `docs/` notes for the local runtime.
