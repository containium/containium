(ns containium.repl
  (:require [containium.reactor]))

(defn find-module
  "Find and deref a module by its name.
   Throws IllegalArgumentException when there's no module with the given `module-name`."
  [module-name]
  (let [agents (-> containium.reactor/systems :modules :agents (deref))]
    (or (some-> agents (get module-name) (deref))
        (throw (IllegalArgumentException.
                 ^String (apply str "Could not find '" module-name "'. Active modules are: "
                                (->> agents (keys) (interpose ", "))))))))

(defn find-deployed-box
  "Find a module by name and if it's activated return its :box.
   Throws IllegalArgumentException when the module is not activated."
 [module-name]
  (or (-> (find-module module-name) :box)
      (throw (IllegalArgumentException. (str "'" module-name "' is not activated")))))

(defn list-active-modules
  "Returns a seq of all active module names"
  []
  (for [[module-name module] (-> containium.reactor/systems :modules :agents (deref))
        :when (some-> module deref :box :box-cl)]
    module-name))

(defn context-classloader
  "Recursively walk the parent classloaders of the current Thread until
   a BoxureClassLoader or the root classloader is found."
  []
  (loop [cl (.getContextClassLoader (Thread/currentThread))]
    (if (or (nil? (.getParent cl))
            (instance? boxure.BoxureClassLoader cl))
      cl
    ;else
      (recur (.getParent cl)))))

(defn eval-context?
  "Return the name of the current module (or ROOT) in which eval is excuted."
  []
  (let [cl (context-classloader)]
    (if-not (instance? boxure.BoxureClassLoader cl)
      "ROOT"
    ;else
      (->> containium.reactor/systems :modules :agents (deref)
           (map (fn [[k v]] [(->> @v :box :box-cl) k]))
           (some (fn [[box-cl box-name]] (when (identical? box-cl cl) box-name)))))))

(defn set-eval-context!
  "Switch the context in which eval is executed.
   Accepts a name of a module, or :ROOT for the reactor (root) context.
   After switching, REPL auto-completion will work within the module."
  [module-name]
  (let [module-name (name module-name)]
    (.setContextClassLoader (Thread/currentThread)
      (if  (= "ROOT" module-name)
        (.getClassLoader clojure.lang.RT)
      ;else
        (:box-cl (find-deployed-box module-name))))
    module-name))

(defn root-eval-context! []
  (set-eval-context! :ROOT))

(defmacro eval-in
  "Evaluate the given `forms` (in an implicit do) in the module identified by `name`."
  [box-name & forms]
  `(boxure.core/eval (find-deployed-box ~(name box-name))
                     '(try (do ~@forms)
                           (catch Throwable e# (do (.printStackTrace e#) e#)))))

(defmacro command
  "Call console commands from the REPL. For example:
  (command module versions foo)."
  [cmd & args]
  (let [cmd (str cmd)
        args (mapv str args)]
    `(print (with-out-str
              (handle-command ~cmd ~args systems
                              (logging/stdout-command-logger (:logging systems) ~cmd))))))