#!/bin/bash
# Debezium Outbox Connector 등록 스크립트
# Kafka Connect REST API를 통해 connector를 등록한다.

CONNECT_URL="http://localhost:8083"

echo "=== Waiting for Kafka Connect to be ready..."
until curl -s "$CONNECT_URL/" > /dev/null 2>&1; do
  sleep 2
done
echo "=== Kafka Connect is ready."

# Battle 서비스 outbox CDC connector
echo "=== Registering battle-outbox-connector..."
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "battle-outbox-connector",
    "config": {
      "connector.class": "io.debezium.connector.mysql.MySqlConnector",
      "tasks.max": "1",
      "database.hostname": "todongsan-mysql",
      "database.port": "3306",
      "database.user": "root",
      "database.password": "1234",
      "database.connectionTimeZone": "Asia/Seoul",
      "database.server.id": "10001",
      "topic.prefix": "cdc-battle",
      "database.include.list": "battle",
      "table.include.list": "battle.outbox_event",
      "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
      "schema.history.internal.kafka.topic": "_schema-history-battle",

      "transforms": "outbox",
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "event_id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.field.event.timestamp": "created_at",
      "transforms.outbox.route.topic.replacement": "${routedByValue}",
      "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType",
      "transforms.outbox.route.by.field": "event_type",
      "transforms.outbox.table.expand.json.payload": "false",

      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.storage.StringConverter"
    }
  }' 2>&1 | python3 -m json.tool

echo ""

# Member-Point 서비스 outbox CDC connector
echo "=== Registering memberpoint-outbox-connector..."
curl -s -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "memberpoint-outbox-connector",
    "config": {
      "connector.class": "io.debezium.connector.mysql.MySqlConnector",
      "tasks.max": "1",
      "database.hostname": "todongsan-mysql",
      "database.port": "3306",
      "database.user": "root",
      "database.password": "1234",
      "database.connectionTimeZone": "Asia/Seoul",
      "database.server.id": "10002",
      "topic.prefix": "cdc-memberpoint",
      "database.include.list": "memberpoint",
      "table.include.list": "memberpoint.outbox_event",
      "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
      "schema.history.internal.kafka.topic": "_schema-history-memberpoint",

      "transforms": "outbox",
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "event_id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.field.event.timestamp": "created_at",
      "transforms.outbox.route.topic.replacement": "${routedByValue}",
      "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType",
      "transforms.outbox.route.by.field": "event_type",
      "transforms.outbox.table.expand.json.payload": "false",

      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.storage.StringConverter"
    }
  }' 2>&1 | python3 -m json.tool

echo ""
echo "=== Connector registration complete."
echo "=== Check status: curl http://localhost:8083/connectors?expand=status"
