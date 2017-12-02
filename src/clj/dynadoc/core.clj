(ns dynadoc.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.repl :as repl]
            [clojure.walk :as walk]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [redirect not-found]]
            [ring.util.request :refer [body-string]]
            [org.httpkit.server :refer [run-server]]
            [rum.core :as rum]
            [dynadoc.common :as common]
            [dynadoc.static :as static]
            [dynadoc.utils :as u]
            [eval-soup.core :as es])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(defonce *web-server (atom nil))
(defonce *options (atom nil))

(defn get-cljs-nses-and-vars-dynamically []
  (when-let [*env (:cljs-env @*options)]
    (require 'cljs.analyzer.api)
    (let [all-ns (resolve (symbol "cljs.analyzer.api" "all-ns"))
          ns-publics (resolve (symbol "cljs.analyzer.api" "ns-publics"))
          get-clj-vars (fn [ns-sym]
                         (reduce
                           (fn [v [var-sym {:keys [doc arglists anonymous]}]]
                             (if anonymous
                               v
                               (conj v
                                 {:sym var-sym
                                  :meta {:doc doc
                                         :arglists (if (= 'quote (first arglists))
                                                     (second arglists)
                                                     arglists)}})))
                           []
                           (ns-publics *env ns-sym)))]
      (reduce
        (fn [m ns-sym]
          (assoc m ns-sym (get-clj-vars ns-sym)))
        {}
        (all-ns *env)))))

(defn get-cljs-nses-and-vars []
  (or (get-cljs-nses-and-vars-dynamically)
      (u/flatten-vals (static/get-cljs-nses-and-vars))))

(defn get-cljs-nses [cljs-nses-and-vars]
  (map #(hash-map
          :sym %
          :type :cljs
          :var-syms (mapv :sym (get cljs-nses-and-vars %)))
    (keys cljs-nses-and-vars)))

(defn get-cljs-vars [cljs-nses-and-vars ns]
  (->> (get cljs-nses-and-vars ns)
       (sort-by #(-> % :sym str))
       vec))

(defn get-clj-nses []
  (map #(hash-map
          :sym (ns-name %)
          :type :clj
          :var-syms (vec (keys (ns-publics %))))
    (all-ns)))

(defn get-defexample-registry []
  (try
    (require 'dynadoc.example)
    (var-get (resolve (symbol "dynadoc.example" "registry-ref")))
    (catch Exception _)))

(defn get-clj-examples [ns-sym]
  (when-let [registry-ref (get-defexample-registry)]
    (reduce
      (fn [m [k v]]
        (assoc m k {:sym k
                    :examples (vec
                                (for [i (range (count v))]
                                  (-> (get v i)
                                      u/process-example
                                      (assoc :id (str ns-sym "/" k "/" i)))))}))
      {}
      (get @registry-ref ns-sym))))

(defn get-clj-var-info [ns-sym var-sym]
  (let [sym (symbol (str ns-sym) (str var-sym))]
    {:sym var-sym
     :meta (-> sym
               find-var
               meta
               (select-keys [:arglists :doc]))
     :source (try (repl/source-fn sym)
               (catch Exception _))
     :spec (try
             (require 'clojure.spec.alpha)
             (let [form (resolve (symbol "clojure.spec.alpha" "form"))]
               (with-out-str
                 (clojure.pprint/pprint
                   (form sym))))
             (catch Exception _))
     :examples (when-let [registry-ref (get-defexample-registry)]
                 (let [examples (get-in @registry-ref [ns-sym var-sym])]
                   (vec
                     (for [i (range (count examples))]
                       (-> (get examples i)
                           u/process-example
                           (assoc :id (str ns-sym "/" var-sym "/" i)))))))}))

(defn get-clj-vars [ns-sym]
  (->> (ns-publics ns-sym)
       keys
       (reduce
         (fn [m var-sym]
           (assoc m var-sym (get-clj-var-info ns-sym var-sym)))
         {})
       (merge (get-clj-examples ns-sym))
       vals
       (sort-by #(-> % :sym str))
       vec))

(defn page [uri {:keys [type ns-sym var-sym static? nses cljs-nses-and-vars] :as opts}]
  (let [cljs-nses-and-vars (or cljs-nses-and-vars (get-cljs-nses-and-vars))
        nses (or nses
                 (->> (concat (get-clj-nses) (get-cljs-nses cljs-nses-and-vars))
                      (sort-by #(-> % :sym str))
                      vec))
        vars (case type
               :clj (cond
                      var-sym [(get-clj-var-info ns-sym var-sym)]
                      ns-sym (get-clj-vars ns-sym))
               :cljs (cond
                       var-sym [(some (fn [var]
                                        (when (-> var :sym (= var-sym))
                                          var))
                                  (get-cljs-vars cljs-nses-and-vars ns-sym))]
                       ns-sym (get-cljs-vars cljs-nses-and-vars ns-sym))
               nil)
        rel-path (-> (remove empty? (str/split uri #"/"))
                     count
                     (- 1)
                     (repeat "../")
                     str/join)
        state (atom (merge opts
                      {:static? static?
                       :nses nses
                       :ns-meta (when (= type :clj)
                                  (some-> ns-sym the-ns meta))
                       :vars vars
                       :rel-path rel-path}))]
    (-> "template.html" io/resource slurp
        (str/replace "{{rel-path}}" rel-path)
        (str/replace "{{content}}" (rum/render-html (common/app state)))
        (str/replace "{{initial-state}}" (pr-str @state)))))

(def public-files
  ["main.js"
   "paren-soup-dark.css"
   "paren-soup-light.css"
   "style.css"
   "fonts/FiraCode-Bold.otf"
   "fonts/FiraCode-Light.otf"
   "fonts/FiraCode-Medium.otf"
   "fonts/FiraCode-Regular.otf"
   "fonts/FiraCode-Retina.otf"
   "fonts/glyphicons-halflings-regular.eot"
   "fonts/glyphicons-halflings-regular.svg"
   "fonts/glyphicons-halflings-regular.ttf"
   "fonts/glyphicons-halflings-regular.woff"
   "fonts/glyphicons-halflings-regular.woff2"])

(defn export [{:strs [pages export-filter type ns-sym var-sym]}]
  (let [type (some-> type keyword)
        ns-sym (some-> ns-sym symbol)
        var-sym (when var-sym
                  (edn/read-string var-sym))
        zip-file (io/file "dynadoc-export.zip")]
    (with-open [zip (ZipOutputStream. (io/output-stream zip-file))]
      (case pages
        "single"
        (cond
          (some? var-sym)
          (do
            (.putNextEntry zip (ZipEntry. "index.html"))
            (io/copy (page
                       "/index.html"
                       {:type type
                        :ns-sym ns-sym
                        :var-sym var-sym
                        :static? true
                        :hide-sidebar? true})
              zip)
            (.closeEntry zip))
          (some? ns-sym)
          (let [cljs-nses-and-vars (get-cljs-nses-and-vars)
                var-syms (map :sym
                           (case type
                             :cljs (get-cljs-vars cljs-nses-and-vars ns-sym)
                             :clj (get-clj-vars ns-sym)))]
            (.putNextEntry zip (ZipEntry. "index.html"))
            (io/copy (page
                       "/index.html"
                       {:type type
                        :ns-sym ns-sym
                        :static? true
                        :hide-sidebar? true
                        :cljs-nses-and-vars cljs-nses-and-vars})
              zip)
            (.closeEntry zip)
            (doseq [var-sym var-syms
                    :let [var-name (-> var-sym str (str/replace "?" "_q"))
                          path (str (name type) "/" ns-sym "/" var-name ".html")]]
              (.putNextEntry zip (ZipEntry. path))
              (io/copy (page path
                         {:type type
                          :ns-sym ns-sym
                          :var-sym var-sym
                          :static? true
                          :hide-sidebar? true
                          :cljs-nses-and-vars cljs-nses-and-vars})
                zip)
              (.closeEntry zip))))
        "multiple"
        (let [clj-nses (get-clj-nses)
              cljs-nses-and-vars (get-cljs-nses-and-vars)
              cljs-nses (get-cljs-nses cljs-nses-and-vars)
              nses (if-let [search (some-> export-filter re-pattern)]
                     (filter #(re-find search (-> % :sym str))
                       (sort-by :sym (concat clj-nses cljs-nses)))
                     (sort-by :sym (concat clj-nses cljs-nses)))]
          (.putNextEntry zip (ZipEntry. "index.html"))
          (io/copy (page "/index.html"
                     {:static? true
                      :cljs-nses-and-vars cljs-nses-and-vars
                      :nses nses})
            zip)
          (.closeEntry zip)
          (doseq [{:keys [sym type var-syms]} nses
                  :let [path (str (name type) "/" sym ".html")]]
            (.putNextEntry zip (ZipEntry. path))
            (io/copy (page path
                       {:type type
                        :ns-sym sym
                        :static? true
                        :cljs-nses-and-vars cljs-nses-and-vars
                        :nses nses})
              zip)
            (.closeEntry zip)
            (doseq [var-sym var-syms
                    :let [var-name (-> var-sym str (str/replace "?" "_q"))
                          path (str (name type) "/" sym "/" var-name ".html")]]
              (.putNextEntry zip (ZipEntry. path))
              (io/copy (page path
                         {:type type
                          :ns-sym sym
                          :var-sym var-sym
                          :static? true
                          :cljs-nses-and-vars cljs-nses-and-vars
                          :nses nses})
                zip)
              (.closeEntry zip)))))
      (doseq [path public-files]
        (.putNextEntry zip (ZipEntry. path))
        (io/copy
          (io/input-stream
            (or (io/resource (str "dynadoc-extend/" path))
                (io/resource (str "dynadoc-public/" path))))
          zip)
        (.closeEntry zip)))
    zip-file))

(defn handler [{:keys [uri] :as request}]
  (or (case uri
        "/"
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (page uri {:static? false})}
        "/eval"
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body (->> request
                    :body
                    .bytes
                    slurp
                    edn/read-string
                    es/code->results
                    (mapv u/form->serializable)
                    pr-str)}
        "/dynadoc-export.zip"
        {:status 200
         :headers {"Content-Type" "application/zip"}
         :body (export (:params request))}
        nil)
      (let [[type ns-sym var-sym] (remove empty? (str/split uri #"/"))
            type (some-> type keyword)
            ns-sym (some-> ns-sym symbol)
            var-sym (some-> var-sym (java.net.URLDecoder/decode "UTF-8") edn/read-string)]
        (when (contains? #{:clj :cljs} type)
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (page uri {:static? false :type type :ns-sym ns-sym :var-sym var-sym})}))
      (not-found "Page not found")))

(defn print-server [server]
  (println
    (str "Started Dynadoc on http://localhost:"
      (-> server meta :local-port)))
  server)

(defn start
  ([opts]
   (-> handler
       (wrap-resource "dynadoc-public")
       (wrap-resource "dynadoc-extend")
       (start opts)))
  ([app opts]
   (when-not @*web-server
     (->> (merge {:port 0} opts)
          (reset! *options)
          (run-server (-> app wrap-content-type wrap-params wrap-keyword-params))
          (reset! *web-server)
          print-server))))

(defn dev-start [opts]
  (when-not @*web-server
    (.mkdirs (io/file "target" "dynadoc-public"))
    (start (-> #'handler
               (wrap-reload)
               (wrap-file "target/dynadoc-public"))
      opts)))

