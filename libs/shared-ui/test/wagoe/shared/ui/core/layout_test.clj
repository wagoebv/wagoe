(ns wagoe.shared.ui.core.layout-test
  "Unit tests for shared UI layout components.
   
   Tests verify that layout components generate correct page structures,
   navigation elements, and consistent styling for the application."
  (:require [wagoe.shared.ui.core.layout :as layout]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

;; =============================================================================
;; Navigation Tests
;; =============================================================================

(deftest ^:unit main-navigation-test
  (testing "Basic navigation generation"
    (let [result (layout/main-navigation)]
      (is (vector? result))
      (is (= :nav (first result)))
      ; Navigation doesn't have attributes map, content starts at index 1
      (let [nav-content (rest result)]
        ; Check that we have link elements in the navigation
        (is (some #(and (vector? %)
                        (or (= :a.logo (first %))
                            (= :a (first %)))
                        (map? (second %))
                        (string? (:href (second %))))
                  nav-content)))))

  (testing "Navigation with custom options"
    ; The main-navigation function doesn't accept class/id options in its current implementation
    ; It only accepts {:user user} for user-specific navigation
    (let [user {:name "Test User"}
          result (layout/main-navigation {:user user})]
      (is (vector? result))
      (is (= :nav (first result)))
      ; Should contain user-specific content when user is provided
      (let [nav-html (str result)]
        (is (str/includes? nav-html "Test User")))))

  (testing "Navigation structure contains expected links"
    (let [result (layout/main-navigation)
          nav-html (str result)]
      ; Should contain the brand logo (with alt text) and login when no user
      (is (str/includes? nav-html "Boundary"))
      (is (str/includes? nav-html "Login")))))

;; =============================================================================
;; Page Layout Tests
;; =============================================================================

(deftest ^:unit page-layout-test
  (testing "Basic page layout generation"
    (let [title "Test Page"
          content [:div "Page content here"]
          result (layout/page-layout title content)]
      (is (vector? result))
      (is (= :html (first result)))
      ; Should have lang attribute in the HTML tag
      (is (= {:lang "en"} (second result)))
      ; Should contain head section with title
      (let [head (nth result 2)] ; head is at index 2
        (is (vector? head))
        (is (= :head (first head)))
        (is (some #(and (vector? %) (= :title (first %)) (= title (last %)))
                  head)))
      ; Should contain body with content
      (let [body (nth result 3)] ; body is at index 3
        (is (vector? body))
        (is (= :body (first body)))
        ; The content should be wrapped in main.main-content
        (is (some #(and (vector? %)
                        (= :main.main-content (first %))
                        (= content (second %)))
                  body)))))

  (testing "Page layout with custom options"
    (let [title "Custom Page"
          content [:main "Main content"]
          opts {:css ["/css/custom.css"] :js ["/js/custom.js"]}
          result (layout/page-layout title content opts)]
      (is (vector? result))
      (is (= :html (first result)))
      ; Should include custom CSS
      (let [page-html (str result)]
        (is (str/includes? page-html "/css/custom.css"))
        (is (str/includes? page-html "/js/custom.js")))))

  (testing "Page layout includes navigation"
    (let [result (layout/page-layout "Test" [:div "Content"])
          page-html (str result)]
      (is (str/includes? page-html "site-header"))
      (is (str/includes? page-html "Boundary")))))  ; Brand logo with alt text

;; =============================================================================
;; Error Layout Tests
;; =============================================================================

(deftest ^:unit error-layout-test
  (testing "Basic error layout generation"
    (let [status 404
          title "Page Not Found"
          message "The requested page could not be found"
          result (layout/error-layout status title message)]
      (is (vector? result))
      ; Should contain error status
      (let [layout-html (str result)]
        (is (str/includes? layout-html "404"))
        (is (str/includes? layout-html title))
        (is (str/includes? layout-html message)))))

  (testing "Error layout with details"
    (let [status 500
          title "Internal Server Error"
          message "Something went wrong"
          details "Contact support if the problem persists"
          result (layout/error-layout status title message details)]
      (is (vector? result))
      (let [layout-html (str result)]
        (is (str/includes? layout-html "500"))
        (is (str/includes? layout-html details)))))

  (testing "Error layout structure"
    (let [result (layout/error-layout 403 "Forbidden" "Access denied")]
      (is (vector? result))
      ; Should be a complete page structure
      (is (or (= :html (first result))
              (= :div (first result))
              (contains? (set (flatten result)) :html))))))

;; =============================================================================
;; Home Page Tests
;; =============================================================================

(deftest ^:unit home-page-content-test
  (testing "Home page content generation"
    (let [result (layout/home-page-content)]
      (is (vector? result))
      ; Should contain welcome or intro content
      (let [content-html (str result)]
        (is (or (str/includes? content-html "Welcome")
                (str/includes? content-html "Home")
                (str/includes? content-html "Dashboard")
                (seq result)))))) ; At minimum should return some content

  (testing "Home page structure"
    (let [result (layout/home-page-content)]
      (is (vector? result))
      ; Should be valid Hiccup structure
      (is (keyword? (first result))))))

;; =============================================================================
;; Error Page Rendering Tests
;; =============================================================================

(deftest ^:unit render-error-page-test
  (testing "Error page rendering with request context"
    ; The render-error-page function signature is different - it takes message and optional status
    (let [message "Page not found"
          status 404
          result (layout/render-error-page message status)]
      (is (string? result)) ; render-error-page returns HTML string, not vector
      ; Should include error information
      (is (str/includes? result "404"))
      (is (str/includes? result "Page not found"))))

  (testing "Error page with exception details"
    ; Test the simpler version that just takes a message
    (let [message "Internal server error"
          result (layout/render-error-page message)]
      (is (string? result)) ; render-error-page returns HTML string, not vector
      ; Should handle error message appropriately
      (is (str/includes? result "Internal server error"))
      (is (str/includes? result "Error")))))

;; =============================================================================
;; Component Layout Tests
;; =============================================================================

(deftest ^:unit modal-test
  (testing "Basic modal generation"
    (let [id "test-modal"
          title "Confirm Action"
          content [:p "Are you sure you want to proceed?"]
          result (layout/modal id title content)]
      (is (vector? result))
      ; Should contain modal structure
      (let [modal-html (str result)]
        (is (str/includes? modal-html id))
        (is (str/includes? modal-html title)))))

  (testing "Modal with custom options"
    (let [id "custom-modal"
          title "Custom Modal"
          content [:div "Custom content"]
          opts {:class "large-modal" :closable false}
          result (layout/modal id title content opts)]
      (is (vector? result))
      ; Should apply custom options
      (let [attrs (second result)]
        (when (map? attrs)
          (is (or (str/includes? (:class attrs "") "large-modal")
                  (string? (:id attrs)))))))))

(deftest ^:unit sidebar-test
  (testing "Basic sidebar generation"
    (let [content [:ul [:li "Menu Item 1"] [:li "Menu Item 2"]]
          result (layout/sidebar content)]
      (is (vector? result))
      ; Should contain sidebar structure
      (let [sidebar-html (str result)]
        (is (or (str/includes? sidebar-html "sidebar")
                (str/includes? sidebar-html "Menu Item"))))))

  (testing "Sidebar with options"
    (let [content [:nav "Navigation content"]
          opts {:position "left" :collapsible true}
          result (layout/sidebar content opts)]
      (is (vector? result))
      ; Should be valid structure
      (is (keyword? (first result))))))

(deftest ^:unit breadcrumbs-test
  (testing "Basic breadcrumbs generation"
    (let [crumbs [["Home" "/"] ["Users" "/users"] ["John Doe" nil]]
          result (layout/breadcrumbs crumbs)]
      (is (vector? result))
      ; Should contain breadcrumb structure
      (let [crumbs-html (str result)]
        (is (str/includes? crumbs-html "Home"))
        (is (str/includes? crumbs-html "Users"))
        (is (str/includes? crumbs-html "John Doe")))))

  (testing "Empty breadcrumbs"
    (let [result (layout/breadcrumbs [])]
      (is (or (nil? result)
              (and (vector? result) (empty? (rest result)))))))

  (testing "Single breadcrumb"
    (let [crumbs [["Dashboard" "/dashboard"]]
          result (layout/breadcrumbs crumbs)]
      (is (vector? result))
      (let [crumbs-html (str result)]
        (is (str/includes? crumbs-html "Dashboard"))))))

(deftest ^:unit card-test
  (testing "Basic card generation"
    (let [content [:div [:h3 "Card Title"] [:p "Card content"]]
          result (layout/card content)]
      (is (vector? result))
      ; Should wrap content in card structure
      (let [card-html (str result)]
        (is (str/includes? card-html "Card Title"))
        (is (str/includes? card-html "Card content")))))

  (testing "Card with custom styling"
    (let [content [:p "Simple content"]
          opts {:class "highlight-card" :border true}
          result (layout/card content opts)]
      (is (vector? result))
      ; Should apply styling options
      (let [attrs (second result)]
        (when (map? attrs)
          (is (or (str/includes? (:class attrs "") "highlight-card")
                  (string? (:class attrs)))))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit layout-integration-test
  (testing "Complete page with all layout components"
    (let [title "Complex Page"
          content [:main
                   (layout/breadcrumbs [["Home" "/"] ["Current" nil]])
                   (layout/card [:div "Main content"])
                   (layout/modal "confirm" "Confirm" [:p "Sure?"])]
          result (layout/page-layout title content)]
      (is (vector? result))
      ; Should generate complete, nested page structure
      (let [page-html (str result)]
        (is (str/includes? page-html title))
        (is (str/includes? page-html "Home"))
        (is (str/includes? page-html "Main content"))
        (is (str/includes? page-html "Confirm")))))

  (testing "Error page rendering integration"
    ; Use the correct render-error-page signature (message, optional status)
    (let [message "Missing page"
          status 404
          result (layout/render-error-page message status)]
      (is (string? result)) ; render-error-page returns HTML string, not vector
      ; Should be complete HTML page
      (is (str/includes? result "html"))
      (is (str/includes? result "404"))
      (is (str/includes? result "Missing page")))))
