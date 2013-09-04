CREATE KEYSPACE ring WITH replication = {
    'class': 'SimpleStrategy',
    'replication_factor': 3
};

CREATE TABLE ring.sessions (
    key varchar PRIMARY KEY,
    data blob
);
