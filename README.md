# Containium

Containium is an application server that ties our systems and modules together. It uses Boxure for horizontal isolation of modules. That means, systems are loaded and run in the "root", whereas modules are loaded and run inside Boxure boxes. This way, modules do not interfere with each other, can be reloaded, and may use the systems in the root.


## Configuration

### Containium configuration

Containium expects a `spec.clj` file to be available as a resource (i.e., on the classpath). This file contains EDN data, holding a map of configuration properties. The following properties are currently supported:

- `:config`, contains a map with keywords corresponding to keywords used to identify the systems. The value of those entries are passed to the system when started.

- `:modules`, a vector of modules loaded at startup. This may change to some other construct.

- `:resolve-dependencies`, a boolean that controls whether the dependencies for modules need to be resolved or are expected to be included in the module jar.


### Systems configuration

The following sections describe the configuration of the systems inside Containium. These configs are expected to be inside the `:config` entry in the `spec.clj` file.


#### Cassandra

The Cassandra system is identified with the `:cassandra` keyword, and the configuration should be a map holding the following entries:

- `:config-file`, a String holding the name of the configuration file that should be available as a resource (i.e, on the classpath).


#### ElasticSearch

The ElasticSearch system is identified with the `:elastic` keyword, but has no configuration at the moment.


#### Kafka

The Kafka system is identified with the `:kafka` keyword, and the configuration should be a map holding the following entries:

- `:port`, a String holding the port number of the Kafka broker.

- `:broker-id`, a String holding the broker ID.

- `:log-dir`, a String holding the path to the message log.

- `:zk-connect`, a String holding the host:port of zookeeper.


#### HTTP Kit

The HTTP Kit system is identified with the `:http-kit` keyword, and the configuration should be a map holding the configuration as should be supplied to the `run-server` function of HTTP Kit. See more on this map [here](http://http-kit.org/server.html).


#### Shared Ring session store

The session store is identified with the `:session-store` keyword, and the configuration should be a map holding the following entries:

- `:ttl`, a number holding the number of minutes a session should live (at least). The session TTL is reset with each request.


### Module configuration

Each module needs to specify a `:containium` entry in the `project.clj` file. That entry must have a map as its value, which can contain the following keys:

- `:start`, this mandatory entry has a namespace qualified symbol as its value, pointing to the start function of the module. This function takes the value of the `:containium` entry of its own `project.clj` as its first argument, and a map with systems as its second argument. The function should return something that will be used as the argument to the stop function of the module (see next point).

- `:stop`, this mandatory entry has a namespace qualified symbol as its value, pointing to the stop function of the module. This function takes the return value of the start function as its argument.

- `:ring`, this optional entry is specified whenever a Ring handler within the module needs to be registered inside the Containium. The value of this entry is a map, which must contain at least a `:handler` entry, having a namespace qualified symbol pointing to the handler function. Optially, one may specify a `:context-path` entry, having a String containing the context path one whishes to run the Ring app in. Note that the `:uri` entry inside a Ring request map does not contain the context in case a `:context-path` entry is specified.
