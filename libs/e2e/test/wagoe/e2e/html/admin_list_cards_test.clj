(ns wagoe.e2e.html.admin-list-cards-test
  "On a narrow screen the admin list shows each row as a compact card: nothing
   overlaps, the pager covers no card, and a select sorts it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private card-geometry
  "Per card: [height, visible cells that overlap one another, cells whose
   content is clipped vertically, whether the title is above the rest, whether
   it fits the list's width, background]."
  "Array.from(document.querySelectorAll('table.data-table tbody tr')).map(tr => {
     const cells = Array.from(tr.querySelectorAll('td:not(.checkbox-cell):not(.actions-cell)'))
       .filter(td => td.getClientRects().length > 0)
       .map(td => ({cls: td.className, r: td.getBoundingClientRect(),
                    clipped: td.scrollHeight > td.clientHeight + 1}));
     const overlaps = [];
     for (let i = 0; i < cells.length; i++)
       for (let j = i + 1; j < cells.length; j++) {
         const a = cells[i].r, b = cells[j].r;
         if (a.left < b.right - 0.5 && b.left < a.right - 0.5 &&
             a.top < b.bottom - 0.5 && b.top < a.bottom - 0.5)
           overlaps.push(cells[i].cls + ' / ' + cells[j].cls);
       }
     const title = cells.find(c => c.cls.includes('c-title'));
     return [tr.getBoundingClientRect().height,
             overlaps,
             cells.filter(c => c.clipped).map(c => c.cls),
             !!title && cells.every(c => c === title || c.r.top >= title.r.bottom - 0.5),
             tr.getBoundingClientRect().right <= document.querySelector('.entity-list-page').getBoundingClientRect().right + 0.5,
             getComputedStyle(tr).backgroundColor];
   })")

(def ^:private pager-geometry
  "With the list scrolled to its end: [the last card's bottom, the pager's
   top, whether the pager is what is under its own centre]."
  "(() => {
     const content = document.querySelector('.admin-content');
     content.scrollTop = content.scrollHeight;
     const rows = document.querySelectorAll('table.data-table tbody tr');
     const pager = document.querySelector('.table-pagination');
     const p = pager.getBoundingClientRect();
     const hit = document.elementFromPoint(p.left + p.width / 2, p.top + p.height / 2);
     return [rows[rows.length - 1].getBoundingClientRect().bottom, p.top, pager.contains(hit)];
   })()")

(defn- titles [pg]
  (page/evaluate pg "Array.from(document.querySelectorAll('table.data-table tbody td.c-title')).map(td => td.textContent.trim())"))

(defn- check-cards! [pg]
  (let [cards (page/evaluate pg card-geometry)]
    (is (< 1 (count cards)) "cards enough to compare")
    (doseq [[height overlaps clipped title-first? fits?] cards]
      (is (empty? overlaps) "no cell overlaps another")
      (is (empty? clipped) "no value is cut off")
      (is (true? title-first?) "the title heads the card")
      (is (true? fits?) "the card fits the list's width")
      (is (< height 120) (str "a compact card, not " height "px")))
    (is (= 1 (count (set (map last cards)))) "every card has the same background")))

(deftest ^:integration ^:e2e on-a-narrow-screen-the-list-is-compact-cards
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (doseq [[w h] [[390 844] [700 900]]
            scheme [:light :dark]]
      (testing (str w "px, " (name scheme))
        (page/set-viewport-size! pg w h)
        (page/emulate-media! pg {:color-scheme scheme})
        ;; Both seeded users, so "the same background" compares two cards
        (page/navigate pg (admin/admin-url "/users"))
        (page/wait-for-load-state pg)
        ;; A hovered card has its own background
        (loc/hover (page/locator pg ".admin-topbar"))
        (check-cards! pg)
        ;; One per page, for a pager
        (page/navigate pg (admin/admin-url "/users?page-size=1"))
        (page/wait-for-load-state pg)
        (let [[last-bottom pager-top pager-on-top?] (page/evaluate pg pager-geometry)]
          (is (<= last-bottom (+ pager-top 0.5)) "the pager covers no card")
          (is (true? pager-on-top?) "nothing is drawn over the pager"))))))

(deftest ^:integration ^:e2e a-select-sorts-the-cards
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/set-viewport-size! pg 390 844)
    (page/navigate pg (admin/admin-url "/users"))
    (page/wait-for-load-state pg)
    (is (loc/is-visible? (page/locator pg ".card-sort select[name='sort']")))
    (admin/sort-by-select! pg "sort" "name")
    ;; The direction select keeps the default sort's desc
    (admin/sort-by-select! pg "dir" "asc")
    (let [ascending (titles pg)]
      (is (< 1 (count ascending)))
      (admin/sort-by-select! pg "dir" "desc")
      (is (str/includes? (page/url pg) "sort=name"))
      (is (str/includes? (page/url pg) "dir=desc"))
      (is (= (reverse ascending) (seq (titles pg)))))

    (testing "a wide screen sorts by its headers instead"
      (page/set-viewport-size! pg 1280 800)
      (is (not (loc/is-visible? (page/locator pg ".card-sort")))))))
