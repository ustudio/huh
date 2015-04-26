(ns huh.core
  (:require-macros)
  (:require
   [cemerick.cljs.test :as t]
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]
   [clojure.string :refer [lower-case]]
   [goog.array :as g-array]
   cljsjs.react
   )
  )

(defprotocol IRendered
  (get-node [c])
  (get-rendered [c])
  (get-props [c]))

(defn do-report [& args]
  (apply t/do-report args)
  )

(defn component-name [component]
  ;; component is a function; this isn't really standardized JS, but
  ;; it works in most browsers anyway.
  (.-name component))

(defn -instrument [sub-component cursor m]
  (dom/div #js {:className "huh-sub-component"
                :data-sub-component sub-component
                :data-cursor cursor
                :data-m m}))

(defn decode-sub-component [sub-component]
  (let [props (get-props sub-component)
        cursor (aget props "data-cursor")]
    {
     :sub-component (aget props "data-sub-component")
     :cursor (if (satisfies? IDeref cursor)
               @cursor
               cursor
               )
     :m (aget props "data-m")
     }
    )
  )

(defn sub-component? [node]
  (.contains (.. node -classList) "huh-sub-component"))

(defn rendered-sub-component [dom-node component]
  (reify IRendered
    (get-node [_] dom-node)
    (get-rendered [_] component)
    (get-props [_] (.. component -props))))

(defn test-predicates
  ;; Checks all predicates against a component, returns true if they
  ;; all pass, or a seq of failures if any failed.
  [preds component msg]
  (if-let [failures
             (seq (filter
                   (fn [result] (not= true result))
                   ((apply juxt preds) component)))]
    (conj failures msg)
    true
    )
  )

(defn setup-state [value]
  (om/setup
   (if (satisfies? IAtom value)
     value
     (atom value))
   (gensym) nil)
  )

(defn built-component
  ([component state] (built-component component state nil))
  ([component state m]
     (binding [om/*state* state]
       (om/build* component (om/to-cursor @state state []) m))
     )
  )

(defn rendered-component
  ([component state] (rendered-component component state nil))
  ([component state m]
     (let [built-c (built-component component state m)
           dom-el (js/document.createElement "div")
           component (js/React.addons.TestUtils.renderIntoDocument built-c)]
       (reify
         IRendered
         (get-node [_] (om/get-node component))
         (get-rendered [_] (.-_renderedComponent component))
         (get-props [_] (.. component -_renderedComponent -props)))
       ))
  )

(defn rendered-element [dom-node react-element]
  (if (sub-component? dom-node)
    (rendered-sub-component dom-node react-element)
    (reify
      IRendered
      (get-node [_] dom-node)
      (get-rendered [_] react-element)
      (get-props [_] (.-props react-element)))))

(defn child-nodes [component]
  (->
   (get-node component)
   (.-children)
   (js/Array.prototype.slice.call)
   (js->clj)))

(defn text? [rendered]
  (string? (get-rendered rendered)))

(defn children-of [component]
  (let [children #js []]
    (.forEach js/React.Children (.-children (get-props component))
              #(.push children %))
    (remove text? (map rendered-element
                       (child-nodes component) (seq children)))))

(defn in
  ([component] (get-node component))
  ([component selector]
     (let [component-node (get-node component)]
       (if (= selector "")
         component-node
         (.querySelector component-node selector)
         )
       )
     )
  )

(defn -extract-m [tests]
  (if (map? (first tests))
    [(first tests) (rest tests)]
    [nil tests]
    )
  )

(defn rendered [component value & tests]
  (let [[m tests] (-extract-m tests)
        state (setup-state value)]
    (binding [om/*instrument* -instrument]
      (let [rendered-comp (rendered-component component state m)]
        (test-predicates tests rendered-comp {:in "rendered component"})
        ))
    )
  )

(defn tag-name [component]
  (->
   (get-node component)
   (.-tagName)
   (lower-case)))

(defn tag [expected-tag & tests]
  (fn -tag-pred [component]
    (let [actual (tag-name component)]
      (if-not (= expected-tag actual)
        {:msg "Tag does not match" :expected expected-tag :actual actual}
        (if (empty? tests)
          true
          (test-predicates tests component {:in (str "tag " expected-tag)})
          )
        )
      )
    )
  )

(defn multiple-components? [component]
  (sequential? component)
  )

(declare display-children)

(defn display-child
  ([component]
     (if (sub-component? (get-node component))
       {:sub-component (->
                        (decode-sub-component component)
                        (:sub-component)
                        (component-name))}
       {:tag (tag-name component)
        :children (display-children (children-of component))})))

(defn display-children [children]
  (if (multiple-components? children)
    (map #(display-child %) children)
    (display-child children)
    )
  )

(defn containing [& tests]
  (fn -containing-pred [component]
    (let [children (children-of component)
          test-count (count tests)
          child-count (count children)]
      (if (not= test-count child-count)
        {:msg "Wrong number of child elements"
         :expected test-count :actual child-count
         :actual-children (display-children children)
         }
        (if-let [failures
                 (seq (filter (fn [result] (not= true result))
                              (map (fn [pred child] (pred child))
                                   tests children
                                   )))]
          failures
          true
          )
        )
      )
    )
  )

(defn with-prop [prop-name expected-value]
  (fn -with-prop-pred [component]
    (let [actual-value (aget (get-props component) prop-name)]
      (if (not= expected-value actual-value)
        {:msg (str "Wrong value for prop '" prop-name "'")
         :expected expected-value :actual actual-value}
        true
        )
      ))
  )

(defn with-text [text]
  (fn -text-pred [component]
    (let [component (get-node component)
          actual-text (.-textContent component)]
      (if (= text actual-text)
        true
        {:msg "Text does not match" :expected text :actual actual-text}
        )
      )
    )
  )

(defn sub-component
  ([expected-component cursor] (sub-component expected-component cursor nil))
  ([expected-component cursor m]
     (fn -sub-component-pred [component]
       (let [expected-name (component-name expected-component)
             {actual-sub-component :sub-component
              actual-cursor :cursor
              actual-m :m} (decode-sub-component component)
             actual-name (component-name actual-sub-component)]
         (cond
          (not= expected-component actual-sub-component)
          {:msg "sub-component does not match"
           :expected expected-name :actual actual-name}

          (not= cursor actual-cursor)
          {:msg (str "sub-component cursor does not match for " expected-name)
           :expected cursor :actual actual-cursor}

          (and (not (nil? m)) (not= m actual-m))
          {:msg (str "sub-component m does not match for " expected-name)
           :expected m :actual actual-m}

          :else true
          )
         )
       )
     )
  )

(defn with-class [class-name]
  (fn -with-class-pred [component]
    (let [node (get-node component)
          actual (.. node -className)]
      (if (.contains (.. node -classList) class-name)
        true
        {:msg "Class name does not match" :expected class-name :actual actual}
        )
      )
    )
  )

(defn with-attr [attr-name attr-value]
  (fn -with-attr-pred [component]
    (let [component (get-node component)
          actual-value (.getAttribute component attr-name)]
      (if (= attr-value actual-value)
        true
        {:msg "Attribute value does not match"
         :expected attr-value :actual actual-value
         :attr attr-name}
        )
      )
    )
  )

(defn after-event [event-attr event-arg dom-node callback]
  ((aget js/React.addons.TestUtils.Simulate (name event-attr))
   dom-node event-arg)
  (callback dom-node)
  )
