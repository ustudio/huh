(ns huh.core
  (:require [cemerick.cljs.test :as t]
            )
  )

(defmethod t/assert-expr 'rendered [msg form]
  `(let [result# ~form]
     (do-report (t/test-context)
                {:type (if (= true result#) :pass :fail), :message ~msg,
                 :expected '~form, :actual result#}
                )
     )
  )

(defmacro with-rendered [params assertion]
  `(fn ~params
     (~assertion ~(nth params 0))
     )
  )
