(ns wagoe.scaffolder.ports
  "Scaffolder module port definitions (abstract interfaces).")

;; =============================================================================
;; Service Layer Ports
;; =============================================================================

(defprotocol IScaffolderService
  "Scaffolder service interface for module generation operations.
   
   This port defines the service layer interface for generating new modules
   from templates. The service orchestrates template rendering, file generation,
   and validation."

  (generate-module [this request]
    "Generate a complete module from template.
     
     Args:
       request: Map conforming to schema/ModuleGenerationRequest
                {:module-name \"customer\"
                 :entities [{:name \"Customer\" :fields [...]}]
                 :interfaces {:http true :cli true :web true}
                 :dry-run false}
     
     Returns:
       Map conforming to schema/ModuleGenerationResult
       {:success true
        :module-name \"customer\"
        :files [{:path \"...\" :content \"...\" :action :create}]
        :errors []
        :warnings []}
       
     Example:
       (generate-module service {:module-name \"customer\" ...})")

  (add-field [this request]
    "Add a field to an existing entity.
     
     Args:
       request: Map with:
                {:module-name \"billing\"
                 :entity \"Invoice\"
                 :field {:name :discount :type :decimal :required true}
                 :dry-run false}
     
     Returns:
       Map with :success, :files (migration + schema patch info), :errors
       
     Example:
       (add-field service {:module-name \"billing\" :entity \"Invoice\" ...})")

  (add-endpoint [this request]
    "Add an endpoint to an existing module.
     
     Args:
       request: Map with:
                {:module-name \"billing\"
                 :path \"/invoices/:id/send\"
                 :method :post
                 :handler-name \"send-invoice\"
                 :dry-run false}
     
     Returns:
       Map with :success, :files (route definition), :errors
       
     Example:
       (add-endpoint service {:module-name \"billing\" :path \"/send\" ...})")

  (add-adapter [this request]
    "Generate a new adapter implementation for a port.
     
     Args:
       request: Map with:
                {:module-name \"cache\"
                 :port \"ICache\"
                 :adapter-name \"redis\"
                 :dry-run false}
     
     Returns:
       Map with :success, :files (adapter file), :errors
       
      Example:
        (add-adapter service {:module-name \"cache\" :port \"ICache\" ...})"))

;; BOU-259: `generate-project` used to live here. It was a second implementation
;; of the `wagoe new` project templates (libs/wagoe-cli/resources/wagoe/cli/
;; templates/) and drifted until it emitted a project with no com.wagoe deps and
;; no entry point. New projects come from the `wagoe` CLI; the scaffolder only
;; works inside an existing one. Supersedes ADR-002.
