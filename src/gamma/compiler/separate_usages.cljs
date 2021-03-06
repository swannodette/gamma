(ns gamma.compiler.separate-usages

  (:require [clojure.set :refer [difference union intersection]])
  (:use [gamma.ast :only [id? gen-term-id]]
        [gamma.compiler.common :only [merge-elements location-conj get-element map-path assoc-in-parent assoc-elements]])
  )


;;;;;;


(defn without
  "Returns set s with x removed."
  [s x] (difference s #{x}))

(defn take-1
  "Returns the pair [element, s'] where s' is set s with element removed."
  [s] {:pre [(not (empty? s))]}
  (let [item (first s)]
    [item (without s item)]))

(defn no-incoming
  "Returns the set of nodes in graph g for which there are no incoming
  edges, where g is a map of nodes to sets of nodes."
  [g]
  (let [nodes (set (keys g))
        have-incoming (apply union (vals g))]
    (difference nodes have-incoming)))

(defn normalize
  "Returns g with empty outgoing edges added for nodes with incoming
  edges only.  Example: {:a #{:b}} => {:a #{:b}, :b #{}}"
  [g]
  (let [have-incoming (apply union (vals g))]
    (reduce #(if (get % %2) % (assoc % %2 #{})) g have-incoming)))

(defn kahn-sort
  "Proposes a topological sort for directed graph g using Kahn's
   algorithm, where g is a map of nodes to sets of nodes. If g is
   cyclic, returns nil."
  ([g]
    (kahn-sort (normalize g) [] (no-incoming g)))
  ([g l s]
    (if (empty? s)
      (when (every? empty? (vals g)) l)
      (let [[n s'] (take-1 s)
            m (g n)
            g' (reduce #(update-in % [n] without %2) g m)]
        (recur g' (conj l n) (union s' (intersection (no-incoming g') m)))))))


(comment
  (def acyclic-g
    {7 [11 8]
     5 #{11}
     3 #{8 10}
     11 #{2 9}
     8 #{9}})

  (def cyclic-g
    {7 #{11 8}
     5 #{11}
     3 #{8 10}
     11 #{2 9}
     8 #{9}
     2 #{11}}) ;oops, a cycle!

  (kahn-sort acyclic-g) ;=> [3 5 7 8 10 11 2 9]
  (kahn-sort cyclic-g) ;=> nil

  )






;;;;;;

(defn get-shared  [x]
  (map
    first
    (filter
      #(let [u (:unconditional (last %)) c (:conditional (last %))]
        (if u (<= 2 (+ u (if c 1 0)))))
      x)))

(defn topological-sort [ids db]
  (let [graph (into {}
                    (map (fn [id]
                           [id (into #{} (filter (:shared (db id) {}) ids))])
                         ids))]

    (if (= {} graph)
      []
      (reverse (kahn-sort graph)))))

(comment
  (defn topological-sort [ids db]
   (let [graph (into {}
                     (map (fn [id]
                            [id (filter (:shared (db id) {}) ids)])
                          ids))]

     (if (= {} graph)
       []
       (reverse (lga/topsort (lg/digraph graph)))))))


(defn shared-elements [id-mappings source-element source-db]
  (let [shared (topological-sort
                 (filter
                  #(not (id-mappings %))
                  (get-shared (:shared source-element)))
                 source-db)]
    (:elements
      (reduce
       (fn [result source-id]
         (let [new-id (gen-term-id)]
           {:elements
                 (conj (:elements result)
                       {:source-id source-id
                        :id        new-id
                        :env       (:env result)
                        :id-mapping (:id-mapping result)})
            :env (conj (:env result) new-id)
            :id-mapping (assoc (:id-mapping result) source-id new-id)}))
       {:elements [] :env #{} :id-mapping {}}
       shared))))

(defn body-elements [source-element id-mappings]
  (mapv
    (fn [y]
      (if-let [id (id-mappings y)]
        {:source-id y :id id }
        {:source-id y :id (gen-term-id) }))
    (:body source-element)))

(defn assoc-attributes [db location source-element bound-ids]
  ;(println [{:id (:id location)} source-element])
  (update-in db
             [(:id location)]
             #(assoc
               (merge (dissoc %1 :source-id) %2)
               :env (into (:env %1) bound-ids))
             (dissoc source-element :id :body :shared)))


(defn separate-usages [source-db id-mapping bound-ids]
  (fn [db location]
    #_(if (= :sin (:head (get-element db location)))
      (println [(bound-ids (:id location)) (:id location)]))
    ;(println (:id location))


    (let [elt (get-element db location)
          bound-ids (into bound-ids (:env elt))
          id-mapping (merge id-mapping (:id-mapping elt))]
      (if (bound-ids (:id location))
       [db nil]
       (let [source-element (source-db (:source-id elt))
             shared (shared-elements id-mapping source-element source-db)
             new-id-mapping (into id-mapping (map #(vector (:source-id %) (:id %)) shared))
             new-bound-ids (into bound-ids (map :id shared))
             body (body-elements source-element new-id-mapping)]



         [(-> (assoc-attributes db location source-element new-bound-ids)
              ;(assoc-in [(:id location) :env] new-bound-ids)
              (merge-elements (location-conj db location :shared) shared)
              (merge-elements (location-conj db location :body) body))
          [[:shared (map-path (separate-usages source-db id-mapping bound-ids))]
           [:body (map-path (separate-usages source-db new-id-mapping new-bound-ids))]]])))))



(comment
  (use 'gamma.compiler.separate-usages)
  (in-ns 'gamma.compiler.separate-usages)

  (use '[gamma.ast :only [id? term]]
       '[gamma.compiler.flatten-ast :only [flatten-ast]])

  (use 'clojure.stacktrace)
  (clojure.stacktrace/e)

  (use 'gamma.ast :reload)


  (def db
    (flatten-ast (let [x (term :foo 1)]
                   (term :bar
                         (term :baz x)
                         (term :baz x)))))

  (use 'gamma.compiler.core)

  (use 'gamma.compiler.bubble-term)
  (def d
    (transform {:root {:source-id :root :id :root}}
              (separate-usages (bubble-terms db) {} #{})))

  (d (gamma.ast.Id. 52))

  (use 'gamma.compiler.print)

  (print-ast d (fn [x db] [(:id (:id x)) (mapv :id (:shared x))]) 30)




  (def x
    ((separate-usages db)
     ))

  (def ops (nth x 1))

  ((nth ops 2)
   {:root (:root db)}
   {:id :root :path []}
   )

  (((nth ops 3) 1) (nth x 0) ((nth ops 3) 0))



  )

(comment


  (defn assoc-shared-elements [db location elements]
    (-> db
        (assoc-elements elements)
        (assoc-in [(:id location) :shared] (mapv :id elements))))

  (defn assoc-body-elements [db location elements]
    (-> db
        (assoc-elements elements)
        (assoc-in [(:id location) :body] (mapv :id elements))))






  (defn parent-id-mappings [db location]
    (or (:id-mappings (get-element db (:parent location))) {}))



  (defn set-id-mappings []
    (fn [db location]
      [(assoc-in
         db [(:id location) :id-mappings]
         (into (parent-id-mappings db location)
               (map #(vector (:source-id %) (:id %))
                    (map db (get-in db [(:id location) :shared]))))) nil]) )

  (defn separate-usages-2 [source-db id-mapping]
   (fn [db location]
     (let [new-id-mapping]
       [new-db
        [[:body (map-path (separate-usages source-db new-id-mapping))]]])))
  (defn separate-usages [source-db id-mapping]
    (fn [db location]
      (if [(usage?)]
        [db nil]
        [
         (separate-usages-sub db location source-db)
         [(set-shared source-db)
          [:shared (map-path (separate-usages source-db))]
          (set-body source-db)
          [:body (map-path (separate-usages source-db))]
          ]])))


  (defn separate-usages [source-db]
    (fn [db location]
      (if [(usage?)]
        [db nil]
        [
         (separate-usages-sub db location source-db)
         [(set-shared source-db)
          [:shared (map-path (separate-usages source-db))]
          (set-body source-db)
          [:body (map-path (separate-usages source-db))]
          ]])))

  (defn set-shared [source-db]
    (fn [db location]
      (let [id-mappings (parent-id-mappings db location)
            x (get-element db location)
            shared (filter #(not (id-mappings %)) (get-shared (:shared x)))
            elements (mapv (fn [x] {:source-id x :id (gen-term-id)}) shared)]
        [(-> db
             (assoc-elements elements)
             (assoc-in [(:id location) :shared] (mapv :id elements))
             (assoc-in [(:id location) :id-mappings]
                       (into id-mappings
                             (map #(vector (:source-id %) (:id %)) elements)))
             (assoc-in [(:id location) :shared-env] (into #{} (map :id elements))))
         nil])))

  (defn set-body [source-db]
    (fn [db location]
      (let [
            elements ]
        [(-> db
             (assoc-elements (filter #(not (id-mappings (:source-id %))) elements))
             (assoc-in [(:id location) :body] (mapv :id elements)))
         nil]))))














