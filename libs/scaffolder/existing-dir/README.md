# existing-dir

A new Wagoe project.

## Getting Started

1. Install dependencies: `clojure -P`
2. Start REPL: `clojure -M:repl`
3. Generate your first module:
   ```bash
   clojure -M:dev -m wagoe.scaffolder.shell.cli-entry generate \
     --module-name user --entity User --field email:string:required:unique
   ```

## Architecture

This project follows the **Functional Core / Imperative Shell** architecture pattern.
