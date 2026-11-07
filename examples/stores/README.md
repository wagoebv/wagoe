# stores — one module, two stores

`examples/shop`'s product module, as the scaffolder wrote it, with a second
repository: `src/shop/product/shell/memory.clj`, over an atom. Nothing in
`examples/shop` changes; this directory depends on it.

- `test/shop/product/shell/repository_contract_test.clj` runs one set of cases
  on the scaffolded SQL repository (H2 and SQLite) and on the atom.
- `test/stores/system_test.clj` boots the shop app on each. `:store :memory`
  under `:wagoe/product` in the config picks the atom (`src/stores/system_config.clj`).

```bash
WAG_ENV=test JWT_SECRET=$(openssl rand -hex 32) clojure -M:test
```

`scripts/example-smoke.sh` runs it in CI. What the contract found is in
`docs/modules/architecture/pages/other-stores.adoc`.
