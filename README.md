# Containium

An application server as a library for Clojure.

[![Join the chat at https://gitter.im/containium/containium](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/containium/containium?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)


## Concepts

Containium is an application server that ties shared root systems and redeployable modules (applications) together. It uses the [Boxure](https://github.com/containium/boxure) library for horizontal isolation of modules. That means, systems are shared among the modules by being loaded and run in the "root" classloader, whereas modules are loaded and run inside Boxure boxes. This way, modules do not interfere with each other, can be reloaded, but can still use the systems in the root.

Containium does not need to be run as an application though. It is also suitable as a library. This way, one can develop and test modules without them being deployed, while still making use of the root systems Containium offers.


### Systems

The systems are the the components that are shared between the modules. Example systems are a service to access [Kafka](http://kafka.apache.org) queues, an integrated [ElasticSearch](http://elasticsearch.org) node and a distributed Ring SessionStore. When Containium is run as an application, they are started automatically before the modules get deployed, and stopped when Containium has gotten the signal to shut down.

Each system implements a protocol. This protocol defines the public API of the system. Multiple actual implementations of this protocol may exist. For example, some systems offer an implementation suitable for module testing and development.

Look in the Codex documentation  for the current systems' APIs.


### Modules

Modules are the components that are isolated from each other, and can be undeployed, redeployed or swapped as much as one would like. Each module uses most of the Clojure runtime in the Containium root, except for the stateful parts, e.g. the namespaces and their definitions. For a complete list of what parts of the Clojure runtime are isolated per module, look at the source [here](https://github.com/containium/boxure/blob/master/src-java/boxure/BoxureClassLoader.java). Most datastructures can be shared between modules, through a root system, if necessary.

Modules can be activated and deactivated. Activating a module while it is undeployed, will deploy it. Activating a module while it is already deployed, will redeploy or swap it.

For a module to be deployable in Containium, it needs to have an `:containium` configuration map in the `project.clj` file or in an external "descriptor" file. This map contains the following entries:

- `:start`, this mandatory entry has a namespace qualified symbol as its value, pointing to the start function of the module. This function takes a map with systems as its first argument, and a module configuration map as its second (see next subsection). The return value of the start function will be used as the argument for the stop function of the module (see next point).

- `:stop`, this mandatory entry has a namespace qualified symbol as its value, pointing to the stop function of the module. This function takes the return value of the start function as its argument.

- `:ring`, this optional entry is specified whenever a Ring handler within the module needs to be registered inside one or more Ring systems in Containium. The value of this entry is a map, which must contain at least a `:handler` entry, having a namespace qualified symbol pointing to the handler function. Optionally, one may specify a `:context-path` entry, having a String containing the context path one whishes to run the Ring app in. Note that the `:uri` entry inside a Ring request map does not contain the context in case a `:context-path` entry is specified. Also note that when a module with a Ring app is deployed, the handler is automatically registered in the root Ring server, i.e. one does not have to do this oneself.

- `:isolates`, this entry will be appended to the `spec.clj` Boxure :isolates configuration. Details are described in the `boxure.core/boxure` function of the [Boxure](https://github.com/containium/boxure) libray.

- `:swappable?`, whenever this optional entry holds a truthy value, the module will be swapped when it is activated while already deployed. This means that the new version of the module is deployed in the background, and when this succeeds, the old module will be undeployed. If a ring handler is specified for the module, the new handler will overwrite the old handler as soon as the new module is deployed successfully.


### Module configuration map

Each module is started with a configuration map as its second argument. This map is built from Leiningen profiles, as well as deployment descriptors (see below).

Containium guarantees this map contains the following entries:

- `:file`, holding a java.io.File containing the path to the module jar or directory.

- `:profiles`, a vector that contains the profile keywords that were requested to apply to the project map of the module.

- `:active-profiles` is *non-overridable* and populated by Containium itself. It contains a set of all active lein profiles.

- `:dev?`, boolean true or false to indicate if the module should run in (local) development mode.

- `:containium`, the final "module specific systems configuration" used by Containium, after merging all active profiles and the [module deployment descriptor](#deploying-clojure-files-deployment-descriptors).

- Any other arbitrary key-value pair that was set in the module deployment descriptor.


## Using Containium as a library

Modules use the systems offered by Containium. Containium has been designed in such a way, that developing and testing these modules can be done without having to run Containium. When Containium is run, it is just running a default and integrated setup of root systems using the `containium.systems/with-systems` macro (and adding some other extras, such as the command loop).

When developing or running the modules standalone, one can easily simulate the Containium, by using the `with-systems` macro. This way, one has full control of what systems are started, how they are configured, et cetera.

For an example module, one could have the following `example_module/core.clj` file:

```clojure
(ns example-module.core
  (:require [containium.systems :refer (with-systems require-system)]
            [containium.systems.config :refer (map-config)]
            [containium.systems.ring.http-kit :refer (test-http-kit)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.store :refer (SessionStore)]
            [ring.middleware.session.memory :refer (memory-store)])

;;; The ring app, without a session store yet, because that is added by the start function.

(defn app [request] (str "Hi, your session is: " (:session request)))


;;; Each module has a start function, taking systems.

(defn start [systems conf]
  ;; The systems local here is just a map with root systems. To get a system that
  ;; implements a specific service protocol, the helper function `require-system`
  ;; can be used. This function also throws an exception when no or multiple
  ;; systems satisfy the given protocol.
  (let [ss (require-system SessionStore systems)
    (alter-var-root #'app #(wrap-session % {:store ss})))))


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

Now one can run the example-module using the Containium systems, without actually being deployed. To run this module with `lein run`, one needs to add `:main example-module.core/main` to the `project.clj` file. To make this module deployable in Containium, the minimun one would need to add to `project.clj` or a descriptor file is:

```clojure
:containium {:start example-module.core/start
            :stop example-module.core/stop
            :ring {:handler example-module.core/app}}
```


## Using Containium as an application

This section describes how the Application server is used when run as an application.

### Running

Currently, this is done by calling `lein run` within the root of `containium/`. After all the systems are started, one enters a command loop (see section below for more on this) and the initial modules will be activated. Containium might also need some configuration changes, which is described in the next section.


### Configuration

Containium expects a `spec.clj` file to be available as a resource (i.e., on the classpath). This file contains EDN data, holding a map of configuration properties. This configuration is read by the `Config` system, and made available to the other systems (and modules) in that form. Each system uses its own entry in this map for its configuration. See the Codox documentation  for more information on which configuration maps are supported.


### Deploying

Deploying and undeploying modules can be done on the command line, but programmatically it is easier done via the file system or a socket connection.

#### Deploying via the file system

Containium watches a directory, as configured in `spec.clj` under the `:fs` key. One can add jar or descriptor files (see below) here one wishes to deploy, but it is adviced to put symlinks to those files in the directory. Using symlinks has the added benefit that one can deploy the same module multiple times, as it is the name of the symlink that is used to identify the module in the Containium. Another benefit is that one can deploy directories this way as well (see below).

To activate a module `X`, one needs to create (e.g. `touch`) a file called `X.activate`. If the module is currently undeployed, it will start to deploy. If it was already deployed, it will redeploy or swap the module. The `X.activate` file is removed by Containium. A file called `X.status` is written by Containium which holds the constantly updated status of the module. One can expect statusses in here like `deploying`, `deployed`, `redeploying`, `swapping` or `undeployed`.

To deactivat a module `X`, one needs to remove the file `X`, or rename it to an ignored file name (starting with a `.` or ending with `.deactivated`).

Whenever a module is _successfully_ deployed through any other means, such as the command line or a socket, its descriptor is automatically written to the watched directory. On deactivation it is removed automatically. This is because all the (symlinks to) JARs and descriptors (except hidden files, and those ending in `.deactivated` and `.status`) in the watched directory are activated automatically on Containium startup.

#### Deploying via command line

To activate a module through the command line, one uses the `module activate` command. This takes at least the name of the module as its first argument. When the path to the project, JAR or descriptor is not yet known by containium, or a new path needs to specified, that path needs to be the second argument.

To deactivate a module, the `module deactivate` command is used, supplying the name of the module to deactivate as its sole argument.

#### Deploying via a socket

Deploying via a socket is almost the same as deploying with a command line, except one sends the command `activate` or `deactivate` to Containium via a TCP connection. The arguments work the same. One can use `netcat` or `telnet` for example to do this. The logging generated by Containium is send back. The last line, before the connection is closed, will be either `SUCCESS` or `FAILED`.

#### Deploying directories

It is common to deploy JAR files, but Containium (and Boxure) also support deploying directories. Such a directory needs to have a `project.clj` file. Boxure will use the `:source-paths`, `:resource-paths` and `:compile-path` entries within the project map as the classpath entries for that module. This feature is especially useful when developing apps using a live containium.

#### Deploying Clojure files (deployment descriptors).

Again, it is common to deploy JAR files, but Containium also supports deploying Clojure files, a.k.a. deployment descriptors. This EDN formatted file must contain a map. The following entries are supported:

- `:file`, a mandatory entry, holding a String containing the path to the module JAR or directory. When the String starts with a forward slash `/`, it is treated as an absolute path, otherwise it is relative to the java (containium) process' working directory (the `user.dir` system property).

- `:containium`, a map that is [meta-merged like Leiningen profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md#merging) onto the `:containium` map from the `project.clj` of the module.

- `:profiles`, a vector that contains the profile keywords one whishes to apply to the project map of the module.

- `:dev?`, defaults to `true` when the :dev lein profile is active. Otherwise, it is just a common key used inside modules however one likes.

- Any arbitrary `:key value` pair (*except* `:active-profiles`) is left as is and merged onto the [Module configuration map](#module-configuration-map).

Using this file, one can influence what is in the `:containium` configuration, e.g. when a JAR contains multiple Ring handlers or one has specified ones own keys in the `:containium` map that one uses in the `:start` function of the module.


### Command line

When running Containium as an application, one enters a command loop after the root systems have started.
It is here where one can, among other things, manage deployments or start a REPL. Type `help` to get an overview of the available commands.

After connecting to the REPL (default port 13337, see project.clj) with a client, one can evaluate code in the `ROOT` context (the Reactor) or switch into deployed applications.
See the [`containium.repl`](https://github.com/containium/containium/blob/master/src/containium/repl.clj) namespace for all available helper functions.