(ns metabase.driver.sql.query-processor
  "The Query Processor is responsible for translating the Metabase Query Language into HoneySQL SQL forms."
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as hsql]
             [format :as hformat]
             [helpers :as h]]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.mbql
             [schema :as mbql.s]
             [util :as mbql.u]]
            [metabase.models
             [field :as field :refer [Field]]
             [table :refer [Table]]]
            [metabase.query-processor
             [interface :as i]
             [store :as qp.store]]
            [metabase.query-processor.middleware.annotate :as annotate]
            [metabase.util
             [honeysql-extensions :as hx]
             [i18n :refer [tru]]]
            [schema.core :as s]))

;; TODO - yet another `*query*` dynamic var. We should really consolidate them all so we only need a single one.
(def ^:dynamic *query*
  "The outer query currently being processed."
  nil)

(def ^:dynamic *nested-query-level*
  "How many levels deep are we into nested queries? (0 = top level.) We keep track of this so we know what level to
  find referenced aggregations (otherwise something like [:aggregation 0] could be ambiguous in a nested query).
  Each nested query increments this counter by 1."
  0)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            Interface (Multimethods)                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti current-datetime-fn
  "HoneySQL form that should be used to get the current `datetime` (or equivalent). Defaults to `:%now`."
  {:arglists '([driver])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmethod current-datetime-fn :sql [_] :%now)


(defmulti date
  "Return a HoneySQL form for truncating a date or timestamp field or value to a given resolution, or extracting a date
  component."
  {:arglists '([driver unit field-or-value])}
  (fn [driver unit _] [(driver/dispatch-on-initialized-driver driver) unit])
  :hierarchy #'driver/hierarchy)

;; default implementation for `:default` bucketing returns expression as-is
(defmethod date [:sql :default] [_ _ expr] expr)


(defmulti field->identifier
  "Return a HoneySQL form that should be used as the identifier for `field`, an instance of the Field model. The default
  implementation returns a keyword generated by from the components returned by `field/qualified-name-components`.
  Other drivers like BigQuery need to do additional qualification, e.g. the dataset name as well. (At the time of this
  writing, this is only used by the SQL parameters implementation; in the future it will probably be used in more
  places as well.)"
  {:arglists '([driver field])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmethod field->identifier :sql [_ field]
  (apply hsql/qualify (field/qualified-name-components field)))


(defmulti field->alias
  "Return the string alias that should be used to for `field`, an instance of the Field model, i.e. in an `AS` clause.
  The default implementation calls `:name`, which returns the *unqualified* name of the Field.

  Return `nil` to prevent `field` from being aliased."
  {:arglists '([driver field])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmethod field->alias :sql [_ field]
  (:name field))


(defmulti quote-style
  "Return the quoting style that should be used by [HoneySQL](https://github.com/jkk/honeysql) when building a SQL
  statement. Defaults to `:ansi`, but other valid options are `:mysql`, `:sqlserver`, `:oracle`, and `:h2` (added in
  `metabase.util.honeysql-extensions`; like `:ansi`, but uppercases the result).

    (hsql/format ... :quoting (quote-style driver), :allow-dashed-names? true)"
  {:arglists '([driver])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmethod quote-style :sql [_] :ansi)


(defmulti unix-timestamp->timestamp
  "Return a HoneySQL form appropriate for converting a Unix timestamp integer field or value to an proper SQL Timestamp.
  `seconds-or-milliseconds` refers to the resolution of the int in question and with be either `:seconds` or
  `:milliseconds`.

  There is a default implementation for `:milliseconds` the recursively calls with `:seconds` and `(expr / 1000)`."
  {:arglists '([driver seconds-or-milliseconds field-or-value])}
  (fn [driver seconds-or-milliseconds _] [(driver/dispatch-on-initialized-driver driver) seconds-or-milliseconds])
  :hierarchy #'driver/hierarchy)

(defmethod unix-timestamp->timestamp [:sql :milliseconds] [driver _ expr]
  (unix-timestamp->timestamp driver :seconds (hx// expr 1000)))


(defmulti apply-top-level-clause
  "Implementations of this methods define how the SQL Query Processor handles various top-level MBQL clauses. Each
  method is called when a matching clause is present in `query`, and should return an appropriately modified version
  of `honeysql-form`. Most drivers can use the default implementations for all of these methods, but some may need to
  override one or more (e.g. SQL Server needs to override this method for the `:limit` clause, since T-SQL uses `TOP`
  instead of `LIMIT`)."
  {:arglists '([driver top-level-clause honeysql-form query]), :style/indent 2}
  (fn [driver top-level-clause _ _]
    [(driver/dispatch-on-initialized-driver driver) top-level-clause])
  :hierarchy #'driver/hierarchy)

(defmethod apply-top-level-clause :default [_ _ honeysql-form _]
  honeysql-form)

;; this is the primary way to override behavior for a specific clause or object class.

(defmulti ->honeysql
  "Return an appropriate HoneySQL form for an object. Dispatches off both driver and either clause name or object class
  making this easy to override in any places needed for a given driver."
  {:arglists '([driver x]), :style/indent 1}
  (fn [driver x]
    [(driver/dispatch-on-initialized-driver driver) (mbql.u/dispatch-by-clause-name-or-class x)])
  :hierarchy #'driver/hierarchy)


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Low-Level ->honeysql impls                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod ->honeysql [:sql nil]    [_ _]    nil)
(defmethod ->honeysql [:sql Object] [_ this] this)

(defmethod ->honeysql [:sql :value] [driver [_ value]] (->honeysql driver value))

(defmethod ->honeysql [:sql :expression]
  [driver [_ expression-name]]
  ;; Unfortunately you can't just refer to the expression by name in other clauses like filter, but have to use the
  ;; original formula.
  (->honeysql driver (mbql.u/expression-with-name *query* expression-name)))

(defn cast-unix-timestamp-field-if-needed
  "Wrap a `field-identifier` in appropriate HoneySQL expressions if it refers to a UNIX timestamp Field."
  [driver field field-identifier]
  (condp #(isa? %2 %1) (:special_type field)
    :type/UNIXTimestampSeconds      (unix-timestamp->timestamp driver :seconds      field-identifier)
    :type/UNIXTimestampMilliseconds (unix-timestamp->timestamp driver :milliseconds field-identifier)
    field-identifier))

(defmethod ->honeysql [:sql (class Field)]
  [driver field]
  (let [table            (qp.store/table (:table_id field))
        field-identifier (keyword (hx/qualify-and-escape-dots (:schema table) (:name table) (:name field)))]
    (cast-unix-timestamp-field-if-needed driver field field-identifier)))

(defmethod ->honeysql [:sql :field-id]
  [driver [_ field-id]]
  (->honeysql driver (qp.store/field field-id)))

(defmethod ->honeysql [:sql :fk->]
  [driver [_ _ dest-field-clause :as fk-clause]]
  ;; because the dest field needs to be qualified like `categories__via_category_id.name` instead of the normal
  ;; `public.category.name` we will temporarily swap out the `categories` Table in the QP store for the duration of
  ;; converting this `fk->` clause to HoneySQL. We'll remove the `:schema` and swap out the `:name` with the alias so
  ;; other `->honeysql` impls (e.g. the `(class Field` one) will do the correct thing automatically without having to
  ;; worry about the context in which they are being called
  (qp.store/with-pushed-store
    (when-let [{:keys [join-alias table-id]} (mbql.u/fk-clause->join-info *query* *nested-query-level* fk-clause)]
      (when table-id
        (qp.store/store-table! (assoc (qp.store/table table-id)
                                 :schema nil
                                 :name   join-alias
                                 ;; for drivers that need to know these things, like Snowflake
                                 :alias? true))))
    (->honeysql driver dest-field-clause)))

(defmethod ->honeysql [:sql :field-literal]
  [driver [_ field-name]]
  (->honeysql driver (keyword (hx/escape-dots (name field-name)))))

(defmethod ->honeysql [:sql :datetime-field]
  [driver [_ field unit]]
  (date driver unit (->honeysql driver field)))

(defmethod ->honeysql [:sql :binning-strategy]
  [driver [_ field _ _ {:keys [bin-width min-value max-value]}]]
  (let [honeysql-field-form (->honeysql driver field)]
    ;;
    ;; Equation is | (value - min) |
    ;;             | ------------- | * bin-width + min-value
    ;;             |_  bin-width  _|
    ;;
    (-> honeysql-field-form
        (hx/- min-value)
        (hx// bin-width)
        hx/floor
        (hx/* bin-width)
        (hx/+ min-value))))


(defmethod ->honeysql [:sql :count] [driver [_ field]]
  (if field
    (hsql/call :count (->honeysql driver field))
    :%count.*))

(defmethod ->honeysql [:sql :avg]      [driver [_ field]] (hsql/call :avg            (->honeysql driver field)))
(defmethod ->honeysql [:sql :distinct] [driver [_ field]] (hsql/call :distinct-count (->honeysql driver field)))
(defmethod ->honeysql [:sql :stddev]   [driver [_ field]] (hsql/call :stddev         (->honeysql driver field)))
(defmethod ->honeysql [:sql :sum]      [driver [_ field]] (hsql/call :sum            (->honeysql driver field)))
(defmethod ->honeysql [:sql :min]      [driver [_ field]] (hsql/call :min            (->honeysql driver field)))
(defmethod ->honeysql [:sql :max]      [driver [_ field]] (hsql/call :max            (->honeysql driver field)))

(defmethod ->honeysql [:sql :+] [driver [_ & args]] (apply hsql/call :+ (map (partial ->honeysql driver) args)))
(defmethod ->honeysql [:sql :-] [driver [_ & args]] (apply hsql/call :- (map (partial ->honeysql driver) args)))
(defmethod ->honeysql [:sql :*] [driver [_ & args]] (apply hsql/call :* (map (partial ->honeysql driver) args)))

;; for division we want to go ahead and convert any integer args to floats, because something like field / 2 will do
;; integer division and give us something like 1.0 where we would rather see something like 1.5
;;
;; also, we want to gracefully handle situations where the column is ZERO and just swap it out with NULL instead, so
;; we don't get divide by zero errors. SQL DBs always return NULL when dividing by NULL (AFAIK)
(defmethod ->honeysql [:sql :/]
  [driver [_ & args]]
  (let [args (for [arg args]
               (->honeysql driver (if (integer? arg)
                                    (double arg)
                                    arg)))]
    (apply hsql/call :/ (first args) (for [arg (rest args)]
                                       (hsql/call :case
                                         (hsql/call := arg 0) nil
                                         :else                arg)))))

(defmethod ->honeysql [:sql :sum-where]
  [driver [_ arg pred]]
  (hsql/call :sum (hsql/call :case
                    (->honeysql driver pred) (->honeysql driver arg)
                    :else                    0.0)))

(defmethod ->honeysql [:sql :count-where]
  [driver [_ pred]]
  (->honeysql driver [:sum-where 1 pred]))

(defmethod ->honeysql [:sql :share]
  [driver [_ pred]]
  (hsql/call :/ (->honeysql driver [:count-where pred]) :%count.*))

(defmethod ->honeysql [:sql :named] [driver [_ ag ag-name]]
  (->honeysql driver ag))

;;  aggregation REFERENCE e.g. the ["aggregation" 0] fields we allow in order-by
(defmethod ->honeysql [:sql :aggregation]
  [driver [_ index]]
  (let [aggregation (mbql.u/aggregation-at-index *query* index *nested-query-level*)]
    (cond
      ;; For some arcane reason we name the results of a distinct aggregation "count",
      ;; everything else is named the same as the aggregation
      (mbql.u/is-clause? :distinct aggregation)
      :count

      (mbql.u/is-clause? #{:+ :- :* :/} aggregation)
      (->honeysql driver aggregation)

      ;; for everything else just use the name of the aggregation as an identifer, e.g. `:sum`
      ;; TODO - this obviously doesn't work right for multiple aggregations of the same type
      :else
      (first aggregation))))

(defmethod ->honeysql [:sql :absolute-datetime]
  [driver [_ timestamp unit]]
  (date driver unit (->honeysql driver timestamp)))

(defmethod ->honeysql [:sql :time]
  [driver [_ value unit]]
  (date driver unit (->honeysql driver value)))

(defmethod ->honeysql [:sql :relative-datetime]
  [driver [_ & args]]
  (match (vec args)
    [0 unit]            (date driver unit (current-datetime-fn driver))
    [amount unit]       (date driver unit (driver/date-interval driver unit amount))
    [field amount unit] (driver/date-interval driver (->honeysql driver field) unit amount)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            Field Aliases (AS Forms)                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(s/defn ^:private qualified-alias :- (s/maybe s/Keyword)
  "Convert the given `field` to a stringified alias, for use in a SQL `AS` clause."
  [driver, field :- (class Field)]
  (some->> field
           (field->alias driver)
           hx/qualify-and-escape-dots))

(s/defn field-clause->alias :- (s/maybe s/Keyword)
  "Generate an approriate alias (e.g., for use with SQL `AN`) for a Field clause of any type."
  [driver, field-clause :- mbql.s/Field]
  (let [expression-name (when (mbql.u/is-clause? :expression field-clause)
                          (second field-clause))
        id-or-name      (when-not expression-name
                          (mbql.u/field-clause->id-or-literal field-clause))
        field           (when (integer? id-or-name)
                          (qp.store/field id-or-name))]
    (cond
      expression-name      (keyword (hx/escape-dots expression-name))
      field                (qualified-alias driver field)
      (string? id-or-name) (keyword (hx/escape-dots id-or-name)))))

(defn as
  "Generate HoneySQL for an `AS` form (e.g. `<form> AS <field>`) using the name information of a `field-clause`. The
  HoneySQL representation of on `AS` clause is a tuple like `[<form> <alias>]`.

  In some cases where the alias would be redundant, such as unwrapped field literals, this returns the form as-is.

    (as [:field-literal \"x\" :type/Text])
    ;; -> <compiled-form>
    ;; -> SELECT \"x\"

    (as [:datetime-field [:field-literal \"x\" :type/Text] :month])
    ;; -> [<compiled-form> :x]
    ;; -> SELECT date_extract(\"x\", 'month') AS \"x\""
  ([driver field-clause]
   (as driver (->honeysql driver field-clause) field-clause))
  ([driver form field-clause]
   (if (mbql.u/is-clause? :field-literal field-clause)
     form
     (if-let [alias (field-clause->alias driver field-clause)]
       [form alias]
       form))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Clause Handlers                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; -------------------------------------------------- aggregation ---------------------------------------------------

(defmethod apply-top-level-clause [:sql :aggregation]
  [driver _ honeysql-form {aggregations :aggregation}]
  (loop [form honeysql-form, [ag & more] aggregations]
    (let [form (h/merge-select
                form
                [(->honeysql driver ag)
                 (hx/escape-dots (driver/format-custom-field-name driver (annotate/aggregation-name ag)))])]
      (if-not (seq more)
        form
        (recur form more)))))

;;; ----------------------------------------------- breakout & fields ------------------------------------------------

(defmethod apply-top-level-clause [:sql :breakout]
  [driver _ honeysql-form {breakout-fields :breakout, fields-fields :fields :as query}]
  (as-> honeysql-form new-hsql
    (apply h/merge-select new-hsql (for [field-clause breakout-fields
                                         :when        (not (contains? (set fields-fields) field-clause))]
                                     (as driver field-clause)))
    (apply h/group new-hsql (map (partial ->honeysql driver) breakout-fields))))

(defmethod apply-top-level-clause [:sql :fields]
  [driver _ honeysql-form {fields :fields}]
  (apply h/merge-select honeysql-form (for [field fields]
                                        (as driver field))))


;;; ----------------------------------------------------- filter -----------------------------------------------------

(defn- like-clause
  "Generate a SQL `LIKE` clause. `value` is assumed to be a `Value` object (a record type with a key `:value` as well as
  some sort of type info) or similar as opposed to a raw value literal."
  [driver field value options]
  ;; TODO - don't we need to escape underscores and percent signs in the pattern, since they have special meanings in
  ;; LIKE clauses? That's what we're doing with Druid...
  ;;
  ;; TODO - Postgres supports `ILIKE`. Does that make a big enough difference performance-wise that we should do a
  ;; custom implementation?
  (if (get options :case-sensitive true)
    [:like field                    (->honeysql driver value)]
    [:like (hsql/call :lower field) (->honeysql driver (update value 1 str/lower-case))]))

(s/defn ^:private update-string-value :- mbql.s/value
  [value :- (s/constrained mbql.s/value #(string? (second %)) "string value"), f]
  (update value 1 f))

(defmethod ->honeysql [:sql :starts-with] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update-string-value value #(str % \%)) options))

(defmethod ->honeysql [:sql :contains] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update-string-value value #(str \% % \%)) options))

(defmethod ->honeysql [:sql :ends-with] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update-string-value value #(str \% %)) options))

(defmethod ->honeysql [:sql :between] [driver [_ field min-val max-val]]
  [:between (->honeysql driver field) (->honeysql driver min-val) (->honeysql driver max-val)])


(defmethod ->honeysql [:sql :>] [driver [_ field value]]
  [:> (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [:sql :<] [driver [_ field value]]
  [:< (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [:sql :>=] [driver [_ field value]]
  [:>= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [:sql :<=] [driver [_ field value]]
  [:<= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [:sql :=] [driver [_ field value]]
  [:= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [:sql :!=] [driver [_ field value]]
  [:not= (->honeysql driver field) (->honeysql driver value)])


(defmethod ->honeysql [:sql :and] [driver [_ & subclauses]]
  (apply vector :and (map (partial ->honeysql driver) subclauses)))

(defmethod ->honeysql [:sql :or] [driver [_ & subclauses]]
  (apply vector :or (map (partial ->honeysql driver) subclauses)))

(defmethod ->honeysql [:sql :not] [driver [_ subclause]]
  [:not (->honeysql driver subclause)])

(defmethod apply-top-level-clause [:sql :filter]
  [driver _ honeysql-form {clause :filter}]
  (h/where honeysql-form (->honeysql driver clause)))


;;; -------------------------------------------------- join tables ---------------------------------------------------

(declare build-honeysql-form)

(defn- make-honeysql-join-clauses
  "Returns a seq of honeysql join clauses, joining to `table-or-query-expr`. `jt-or-jq` can be either a `JoinTable` or
  a `JoinQuery`"
  [driver table-or-query-expr {:keys [join-alias fk-field-id pk-field-id]}]
  (let [source-field (qp.store/field fk-field-id)
        pk-field     (qp.store/field pk-field-id)]
    [[table-or-query-expr (keyword join-alias)]
     [:=
      (->honeysql driver source-field)
      (hx/qualify-and-escape-dots join-alias (:name pk-field))]]))

(s/defn ^:private join-info->honeysql
  [driver , {:keys [query table-id], :as info} :- mbql.s/JoinInfo]
  (if query
    (make-honeysql-join-clauses driver (build-honeysql-form driver query) info)
    (let [table (qp.store/table table-id)]
      (make-honeysql-join-clauses driver (->honeysql driver table) info))))

(defmethod apply-top-level-clause [:sql :join-tables]
  [driver _ honeysql-form {:keys [join-tables]}]
  (reduce (partial apply h/merge-left-join) honeysql-form (map (partial join-info->honeysql driver) join-tables)))


;;; ---------------------------------------------------- order-by ----------------------------------------------------

(defmethod apply-top-level-clause [:sql :order-by]
  [driver _ honeysql-form {subclauses :order-by breakout-fields :breakout}]
  (let [[{:keys [special-type] :as first-breakout-field}] breakout-fields]
    (loop [honeysql-form honeysql-form, [[direction field] & more] subclauses]
      (let [honeysql-form (h/merge-order-by honeysql-form [(->honeysql driver field) direction])]
        (if (seq more)
          (recur honeysql-form more)
          honeysql-form)))))

;;; -------------------------------------------------- limit & page --------------------------------------------------

(defmethod apply-top-level-clause [:sql :limit]
  [_ _ honeysql-form {value :limit}]
  (h/limit honeysql-form value))

(defmethod apply-top-level-clause [:sql :page]
  [_ _ honeysql-form {{:keys [items page]} :page}]
  (-> honeysql-form
      (h/limit items)
      (h/offset (* items (dec page)))))


;;; -------------------------------------------------- source-table --------------------------------------------------

(defmethod ->honeysql [:sql (class Table)]
  [_ table]
  (let [{table-name :name, schema :schema} table]
    (hx/qualify-and-escape-dots schema table-name)))

(defmethod apply-top-level-clause [:sql :source-table]
  [driver _ honeysql-form {source-table-id :source-table}]
  (h/from honeysql-form (->honeysql driver (qp.store/table source-table-id))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Building the HoneySQL Form                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private top-level-clause-application-order
  "Order to apply top-level clauses in. This is important because we build things like the `SELECT` clause progressively
  and MBQL requires us to return results with `:breakout` columns before `:aggregation`, etc."
  [:source-table :breakout :aggregation :fields :filter :join-tables :order-by :page :limit])

(defn- query->keys-in-application-order
  "Return the keys present in an MBQL `inner-query` in the order they should be processed."
  [inner-query]
  ;; sort first by any known top-level clauses according to the `top-level-application-clause-order` defined above,
  ;; then sort any unknown clauses by name.
  (let [known-clause->index (into {} (map-indexed (fn [i clause] [clause i]) top-level-clause-application-order))]
    ;; We'll do this using a [<known-applicaton-order-index> <clause-name-keyword>] tuple
    (sort-by (fn [clause] [(known-clause->index clause Integer/MAX_VALUE) clause]) (keys inner-query))))

(defn- apply-top-level-clauses
  "Loop through all the `clause->handler` entries; if the query contains a given clause, apply the handler fn. Doesn't
  handle `:source-query`; since that must be handled in a special way, that is handled separately."
  [driver honeysql-form inner-query]
  (loop [honeysql-form honeysql-form, [k & more] (query->keys-in-application-order inner-query)]
    (let [honeysql-form (apply-top-level-clause driver k honeysql-form inner-query)]
      (if (seq more)
        (recur honeysql-form more)
        ;; ok, we're done; if no `:select` clause was specified (for whatever reason) put a default (`SELECT *`) one
        ;; in
        (update honeysql-form :select #(if (seq %) % [:*]))))))


;;; -------------------------------------------- Handling source queries ---------------------------------------------

(declare apply-clauses)

;; TODO - it seems to me like we could actually properly handle nested nested queries by giving each level of nesting
;; a different alias
(def source-query-alias
  "Alias to use for source queries, e.g.:

    SELECT source.*
    FROM ( SELECT * FROM some_table ) source"
  :source)

(defn- apply-source-query
  "Handle a `:source-query` clause by adding a recursive `SELECT` or native query. At the time of this writing, all
  source queries are aliased as `source`."
  [driver honeysql-form {{:keys [native], :as source-query} :source-query}]
  (assoc honeysql-form
    :from [[(if native
              (hsql/raw (str "(" (str/replace native #";+\s*$" "") ")")) ; strip off any trailing slashes
              (binding [*nested-query-level* (inc *nested-query-level*)]
                (apply-clauses driver {} source-query)))
            source-query-alias]]))

(defn- apply-clauses-with-aliased-source-query-table
  "For queries that have a source query that is a normal MBQL query with a source table, temporarily swap the name of
  that table to the `source` alias and handle other clauses. This is done so `field-id` references and the like
  referring to Fields belonging to the Table in the source query work normally."
  [driver honeysql-form {:keys [source-query], :as inner-query}]
  (qp.store/with-pushed-store
    (when-let [source-table-id (:source-table source-query)]
      (qp.store/store-table! (assoc (qp.store/table source-table-id)
                               :schema nil
                               :name   (name source-query-alias)
                               ;; some drivers like Snowflake need to know this so they don't include Database name
                               :alias? true)))
    (apply-top-level-clauses driver honeysql-form (dissoc inner-query :source-query))))


;;; -------------------------------------------- putting it all togetrher --------------------------------------------

(defn- apply-clauses
  "Like `apply-top-level-clauses`, but handles `source-query` as well, which needs to be handled in a special way
  because it is aliased."
  [driver honeysql-form {:keys [source-query], :as inner-query}]
  (if source-query
    (apply-clauses-with-aliased-source-query-table
     driver
     (apply-source-query driver honeysql-form inner-query)
     inner-query)
    (apply-top-level-clauses driver honeysql-form inner-query)))

(defn build-honeysql-form
  "Build the HoneySQL form we will compile to SQL and execute."
  [driverr {inner-query :query}]
  {:pre [(map? inner-query)]}
  (u/prog1 (apply-clauses driverr {} inner-query)
    (when-not i/*disable-qp-logging*
      (log/debug (tru "HoneySQL Form:") (u/emoji "🍯") "\n" (u/pprint-to-str 'cyan <>)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 MBQL -> Native                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn honeysql-form->sql+args
  "Convert HONEYSQL-FORM to a vector of SQL string and params, like you'd pass to JDBC."
  {:style/indent 1}
  [driver honeysql-form]
  {:pre [(map? honeysql-form)]}
  (let [[sql & args] (try (binding [hformat/*subquery?* false]
                            (hsql/format honeysql-form
                              :quoting             (quote-style driver)
                              :allow-dashed-names? true))
                          (catch Throwable e
                            (log/error (u/format-color 'red
                                           (str (tru "Invalid HoneySQL form:")
                                                "\n"
                                                (u/pprint-to-str honeysql-form))))
                            (throw e)))]
    (into [(hx/unescape-dots sql)] args)))

(defn mbql->native
  "Transpile MBQL query into a native SQL statement."
  [driver {inner-query :query, database :database, :as outer-query}]
  (binding [*query* outer-query]
    (let [honeysql-form (build-honeysql-form driver outer-query)
          [sql & args]  (honeysql-form->sql+args driver honeysql-form)]
      {:query  sql
       :params args})))
