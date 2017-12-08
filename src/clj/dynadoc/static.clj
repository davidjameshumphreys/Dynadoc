(ns dynadoc.static
  (:require [dynadoc.utils :as u]
            [dynadoc.example :as ex]
            [clojure.java.io :as io]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :refer [indexing-push-back-reader]]))

(defn get-cljs-arglists [args]
  (loop [args args
         arglists []]
    (if-let [arg (first args)]
      (cond
        (vector? arg)
        (list arg)
        (and (list? arg) (vector? (first arg)))
        (recur (rest args) (conj arglists (first arg)))
        :else
        (recur (rest args) arglists))
      arglists)))

(defn read-cljs-file [ns->vars f]
  (let [reader (indexing-push-back-reader (slurp f))]
    (loop [current-ns nil
           ns->vars ns->vars]
      (if-let [form (try (r/read {:eof nil} reader)
                      (catch Exception _ '(comment "Reader error")))]
        (recur
          (if (and (list? form)
                   (= (first form) 'ns))
            (second form)
            current-ns)
          (cond
            (and current-ns
                 (list? form)
                 (contains? #{'def 'defn 'defonce} (first form))
                 (not (some-> form second meta :private)))
            (let [[call sym & args] form]
              (update-in ns->vars [current-ns sym] merge
                {:sym sym
                 :meta (merge
                         (when (= call 'defn)
                           {:doc (when (string? (first args))
                                   (first args))
                            :arglists (get-cljs-arglists args)})
                         (select-keys (meta sym) [:arglists :doc]))
                 :source (with-out-str
                           (clojure.pprint/pprint
                             form))}))
            (and current-ns
                 (list? form)
                 (symbol? (first form))
                 (contains? #{'defexample 'defexamples} (-> form first name symbol)))
            (let [[sym k & args] form
                  sym (-> sym name symbol)
                  ns-sym (or (ex/parse-ns k) current-ns)
                  var-sym (ex/parse-val k)
                  examples (case sym
                             defexample [(ex/parse-example args)]
                             defexamples (mapv ex/parse-example args))
                  examples (vec
                             (for [i (range (count examples))]
                               (-> (get examples i)
                                   u/process-example
                                   (assoc :id (str ns-sym "/" var-sym "/" i)))))]
              (update-in ns->vars [ns-sym var-sym] merge
                {:sym var-sym
                 :examples examples}))
            :else ns->vars))
        ns->vars))))

(defn get-cljs-nses-and-vars []
  (loop [files (file-seq (io/file "."))
         ns->vars {}]
    (if-let [f (first files)]
      (if (and (.isFile f)
               (-> f .getName (.endsWith ".cljs")))
        (recur
          (rest files)
          (try
            (read-cljs-file ns->vars f)
            (catch Exception e
              (.printStackTrace e)
              ns->vars)))
        (recur (rest files) ns->vars))
      ns->vars)))
