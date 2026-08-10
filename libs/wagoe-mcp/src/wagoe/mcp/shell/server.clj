(ns wagoe.mcp.shell.server
  "Entry point for the stdio MCP server. Run with: clojure -M:run

   Deliberately requires nothing. stdio MCP puts JSON-RPC on stdout, and
   loading almost any namespace here initialises logging — logback prints its
   configuration status to whatever System/out is at that moment, before a
   single line of this file runs. So stdout is claimed first and the rest of
   the server is loaded afterwards, from wagoe.mcp.shell.boot.")

(defn claim-stdout!
  "Take sole ownership of stdout and return the stream the protocol must use.

   stdio MCP reserves stdout for JSON-RPC, so anything else written there
   corrupts the stream. This library ships a logback.xml targeting stderr for
   exactly that reason, and it is not enough, because the server does not
   control the classpath it runs on:

     * Run from the Wagoe monorepo — which is what the documentation tells you
       to do — the repository's own resources/logback.xml wins and its console
       appender targets stdout.
     * Two logback.xml files on one classpath is itself a configuration
       warning, and any warning makes logback dump its whole status log. Also
       to stdout.

   Measured before this existed: 88 lines of logback output preceded the first
   JSON-RPC response, so a client reading stdout met a banner where it expected
   a message.

   Rather than require every host project to configure logging correctly,
   stdout is taken here and replaced with stderr. Anything that logs or
   printlns afterwards — this server, a library, logback itself — writes to
   stderr, which the MCP spec reserves for exactly that purpose.

   Returns the original stdout, for the transport."
  []
  (let [protocol-out System/out]
    (System/setOut (java.io.PrintStream. System/err true "UTF-8"))
    (alter-var-root #'*out* (constantly (java.io.PrintWriter. System/err true)))
    protocol-out))

(defn -main
  "Claim stdout, then load and start the server. Returns at EOF on stdin."
  [& _args]
  (let [protocol-out (claim-stdout!)]
    ;; Loaded now, not required above: see the namespace docstring.
    (require 'wagoe.mcp.shell.boot)
    ((resolve 'wagoe.mcp.shell.boot/start!) protocol-out)))
