# Containium

An application server as a library for Clojure.


## Concepts

Containium is an application server that ties shared root systems and redeployable modules together. It uses the Boxure library for horizontal isolation of modules. That means, systems that are shared among the modules are loaded and run in the "root", whereas modules are loaded and run inside Boxure boxes. This way, modules do not interfere with each other, can be reloaded, but can still use the systems in the root.

Containium does not need to be run as an application though. It is also suitable as a library. This way, one can develop and test modules without them being deployed, while still making use of the root systems Containium offers.


### Systems

The systems are the the components that are shared between the modules. When Containium is run as an application, they are started automatically before the modules get deployed, and stopped when Containium has gotten the signal to shut down.

Each system implements a protocol. This protocol defines the public API of the system. Multiple actual implementations of this protocol may exist. For example, some systems offer an implementation suitable for module testing and development.

Look in the Codex documentation  for the current systems' APIs.


### Modules

Modules are the components that are isolated from each other, and can be undeployed and redeployed as much as one would like. Each module uses most of the Clojure runtime in the Containium root, except for the stateful parts, e.g. the namespaces and their definitions.

For a module to be deployable in Containium, it needs to have a `:containium` configuration map in the `project.clj` file. This map may contain the following entries:

- `:start`, this mandatory entry has a namespace qualified symbol as its value, pointing to the start function of the module. This function takes a map with systems as its first argument, and a module configuration map. The function should return something that will be used as the argument to the stop function of the module (see next point).

- `:stop`, this mandatory entry has a namespace qualified symbol as its value, pointing to the stop function of the module. This function takes the return value of the start function as its argument. (**MAYBE I MAKE THIS ENTRY OPTIONAL, AS NOT EVERY MODULE NEEDS IT. ON THE OTHER HAND, HAVING IT MANDATORY MAKES ONE THINK EXPLICITLY ABOUT CLEANING UP RESOURCES.**)

- `:ring`, this optional entry is specified whenever a Ring handler within the module needs to be registered inside the Containium. The value of this entry is a map, which must contain at least a `:handler` entry, having a namespace qualified symbol pointing to the handler function. Optionally, one may specify a `:context-path` entry, having a String containing the context path one whishes to run the Ring app in. Note that the `:uri` entry inside a Ring request map does not contain the context in case a `:context-path` entry is specified. Also note that when a module with a Ring app is deployed, the handler is automatically registered in the root Ring server, i.e. one does not have to do this oneself.

- `:isolates`, this entry will be appended to the `spec.clj` Boxure :isolates configuration. Details are described in the `boxure.core/boxure` documentation.


### Module configuration map

Each module is started with a configuration map as its second argument. This map is built from Leiningen profiles, as well as deployment descriptors (see below).

Containium guarantees this map contains the following entries:

- `:file`, holding a java.io.File containing the path to the module jar or directory.

- `:profiles`, a vector that contains the profile keywords that were requested to apply to the project map of the module.

- `:active-profiles` is *non-overridable* and populated by Containium itself. It contains a set of all active lein profiles.

- `:dev?`, boolean true or false to indicate if the module should run in (local) development mode.

- `:containium`, the final "module specific systems configuration" used by Containium, after merging all active profiles and the [module deployment descriptor](#deploying-clojure-files-deployment-descriptors).

- Any other arbitrary :key value pair that was set in the module deployment descriptor.


## Using Containium as a library

Modules use the systems offered by Containium. Containium has been designed in such a way, that developing and testing these modules can be done without having to run Containium. When Containium is run, it is just running a default and integrated setup of root systems using the `containium.systems/with-systems` macro (and adding some other benefits, such as the command loop).

When developing or running the modules standalone, one can easily simulate the Containium, by using the `with-systems` macro. This way, one has full control of what systems are started, how they are configured, et cetera.

For an example module, one could have the following `example_module/core.clj` file:

```clojure
(ns example-module.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.config :refer (map-config)]
            [containium.systems.ring :refer (test-http-kit)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.store :refer (SessionStore)]
            [ring.middleware.session.memory :refer (memory-store)])

;;; The ring app, without a session store yet, because that is added by the start function.

(defn app [request] (str "Hi, your session is: " (:session request)))


;;; Each module has a start function, taking systems.

(defn start [systems conf]
  ;; First it is good to test whether a :session-store system is available.
  (assert (:session-store systems) "Test module requires a :session-store system.")
  (alter-var-root #'app #(wrap-session % {:store (:session-store systems)}))))


;;; Each module has a stop function, taking the result of the start function.

(defn stop [_]) ; Nothing to stop that is module specific.


;;; Have a main function for testing, simulating the Containium systems.

(defn main [& args]
  ;; Create the test systems.
  (with-systems systems [:config (map-config {:http-kit {:port 8080}})
                         :session-store (memory-store)
                         :ring (test-http-kit #'app)]
    ;; Start your application as Containium would.
    (start systems {})
    ;; Do your tests here or wait for input.
    (read-line))) ; Shutdown on enter.
```

Now one can run the example-module using the Containium systems, without actually being deployed. To run this module with `lein run`, one needs to add `:main example-module.core/main` to the `project.clj` file. To make this module deployable in Containium, one would need to add the following to `project.clj`:

```clojure
:containium {:start example-module.core/start
            :stop example-module.core/stop
            :ring {:handler example-module.core/app}}
```


## Using Containium as an application

This section describes how the Application server is used when run as an application.

### Running

Currently, this is done by calling `lein run` within the root of `containium/`. After all the systems are started, one enters a command loop. See the section on this below for more information. Containium might also need some configuration changes, which is described in the next section.


### Configuration

Containium expects a `spec.clj` file to be available as a resource (i.e., on the classpath). This file contains EDN data, holding a map of configuration properties. This configuration is read by the `Config` system, and made available to the other systems (and modules) in that form. See the Codox documentation  for more information on which configuration maps are supported.


### Deploying using file system

Deploying and undeploying modules can be done on the command line, but programmatically it is easier done via the file system.

Containium watches a directory, as configured in `spec.clj` under the `:fs` key. One can add jar files in here for deployment, but it is adviced to put symlinks to those files in the directory. Using symlinks has the added benefit that one can deploy the same module multiple times, as it is the name of the symlink that is used to identify the module in the Containium. Another benefit is that one can edit the jars, without invoking redeployments in the process.

When adding a file `X` to the directory, a file called `X.status` is added which will hold the current state of the `X` module. Note that deployments through the command line also results in a file called `name-given-at-command-line.status`. The following actions are currently supported through the file system:

- When adding file `X` to the directory, and no `X` module is deployed, it is deployed. The `X.status` file will hold `deploying` and subsequently `deployed` or `failed`.

- When touching a file `X` in the directory (i.e. its modification time changes), it is swapped or redeployed (depending on the `:swap-test` value in the `project.clj` file. The status file will hold successively `swapping` or `redeploying`, and `deployed` or `failed` when done. **SWAPPING AND `:swap-test` HAS NOT BEEN IMPLEMENTED YET.**

- When removing a file `X` and module `X` is deployed, it is undeployed. The status file will hold `undeploying` and subsequently `undeployed`.

- When adding a file `X` while a module named `X` is already deployed (via another deployment method), a swap or redeploy is performed (depending on the `:swap-test` value in the `project.clj` file of the *new* module jar). **AGAIN, SWAPPING HAS NOT BEEN IMPLEMENTED YET.**

When all the systems are started, the file system deployer is asked to trigger the deployment of all the files in the deployments directory (ignoring `.status` or hidden files).


#### Deploying directories.

It is common to deploy JAR files, but Containium (and Boxure) also support deploying directories. Such a directory needs to have a `project.clj` file. Boxure will use the `:source-paths`, `:resource-paths` and `:compile-path` entries within the project map as the classpath entries for that module.


#### Deploying Clojure files (deployment descriptors).

Again, it is common to deploy JAR files, but Containium also supports deploying Clojure files. This EDN formatted file must contain a map. The following entries are supported:

- `:file`, a mandatory entry, holding a String containing the path to the module jar or directory. When the String starts with a forward slash `/`, it is treated as an absolute path, otherwise it is relative to the java (containium) process' working directory (the `user.dir` system property).

- `:containium`, a map that is [meta-merged like Leiningen profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md#merging) onto the `:containium` map from the `project.clj` of the module.

- `:profiles`, a vector that contains the profile keywords one whishes to apply to the project map of the module.

- `:dev?`, defaults to `true` when the :dev lein profile is active.

- Any arbitrary `:key value` pair (*except* `:active-profiles`) is left as is and merged onto the [Module configuration map](#module-configuration-map).

Using this file, one can influence what is in the `:containium` configuration, e.g. when a jar contains multiple Ring handlers or one has specified ones own keys in the `:containium` map that one uses in the `:start` function of the module.


### Command line

When running Containium as an application, one enters a command loop after the root systems have started. It is here where one cae, among other things, manage deployments or start a REPL. Type `help` to get an overview of the available commands.
