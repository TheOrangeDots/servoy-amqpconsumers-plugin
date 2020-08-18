# Servoy-AMQPListeners-Plugin
The `AMQPListeners` plugin for [Servoy](http://servoy.com) allows a Servoy ApplicationServer instance to listen to AMQP Queues and when receiving a message will invoke the configured method in a Servoy Solution, utilizing a pool of Headless Clients

# Use case
This plugin was developed to be able to process queued messages in Servoy, without having to rely on a batchprocessor or another type of client running

It has built-in support for RPC-style messaging using AMQP's replyTo and correlationId fields

# Requirements
- Servoy: tested with Servoy 8.x, 2020.06
- RabbitMQ: only tested with RabbitMQ, but should work with anything that supports AMQP

# Installation
Download the `com.tod.servoy.plugins.amqp.consumers-x.x.x.jar` file from the desired [release](https://github.com/TheOrangeDots/servoy-amqplisteners-plugin/releases) and place the downloaded file in the /application_server/plugins folder of [Servoy](http://servoy.com)

# Usage
Using the plugin in a combination of configuring the AMQP-side of things through servoy.properties and implementing a callback function in one (or more) solutions in Servoy.

The callback, for example in solution `mySolution` and scope `amqpMessageHandling` could look like this:
```
/**
 * Callback handler used by the AMQP Consumer plugin to handle incoming Remote Procedure Call messages from JAS
 *
 * @private
 *
 * @param {string} consumerTag - the consumer tag associated with the consumer
 * @param {Packages.com.rabbitmq.client.Envelope} envelope - packaging data for the message
 * @param {Packages.com.rabbitmq.client.AMQP.BasicProperties} properties - content header data for the message
 * @param {byte[]} body - the message body (opaque, client-specific byte array)
 */
function consumeRPC(consumerTag, envelope, properties, body) {
	const headers = properties.getHeaders();
  const payload = JSON.parse(utils.bytesToString(body));

	application.output('Payload: ' + JSON.stringify(payload), LOGGING_LEVEL.INFO);

  // your business logic here
  // ...
  
	return {
    success: true
  };
}
```

In order for the plugin to connect to an AMQP broker like RabbitMQ, a `connection` needs to be defined:
```
tod.queueing.connection.host=localhost
tod.queueing.connection.password=guest
tod.queueing.connection.port=5672
tod.queueing.connection.user=guest
tod.queueing.connection.vhost=myVirtualHost
```
Alternatively the shorthand url property can be used: `tod.queueing.connection.url=amqp://guest:guest@localhost:5672/myVirtualHost`

The plugin also needs to be instructed which `exchange` and `queue` it needs to subscribe to for consuming messages:
```
tod.queueing.exchange.durable=true
tod.queueing.exchange.name=rpc
tod.queueing.exchange.type=direct
tod.queueing.queue.options.x-expires=86400000
tod.queueing.queue.durable=true
tod.queueing.queue.name=rpc.mySolution
```

Lastly, the plugin needs to be instructed which method to call in which Servoy solution to process messages:
```
tod.queueing.consumer.autoAck=true
tod.queueing.consumer.handler=mySolution\://scopes.amqpMessageHandling.consumeRPC
tod.queueing.consumer.noLocal=true
```

Note: all properties for this plugin are prefixed with `tod.queueing.`.

## Defining multiple connections/queues/exchanges/
The config example provided above allows for the configuration of just one of each of the AMQP objects like connections/exchanges/queues/etc. The plugin however allows configuring as many as you need.

The configuration example below adds a `.1` identifier to every property key after the AMQP keyword (connection/channel/exchange/queue/binding/consumer). All properties sharing teh same keywork and identifier belong to the same configuration.

Additionally there are cross-references:
- a channel for example belongs to a connection: `tod.queueing.channel.1.connection=1`
- an exchange belongs to a channel: `tod.queueing.exchange.1.channel=1`
- a queue belongs to a channel: `tod.queueing.queue.1.channel=1`
- a binding needs a reference to both a queue and an exchange: `tod.queueing.binding.1.queue=1 `, `tod.queueing.binding.1.exchange=1`
- a consumer is for a queue: `tod.queueing.consumer.1.queue=1`

```
tod.queueing.connection.1.host=${AMQP_URL}
tod.queueing.connection.1.password=${AMQP_PASS}
tod.queueing.connection.1.port=5672
tod.queueing.connection.1.user=${AMQP_USER}
tod.queueing.connection.1.vhost=integrations

tod.queueing.channel.1.connection=1

tod.queueing.exchange.1.channel=1
tod.queueing.exchange.1.name=rpc

tod.queueing.queue.1.channel=1
tod.queueing.queue.1.name=rpc2.${SUBDOMAIN}

tod.queueing.binding.1.queue=1 
tod.queueing.binding.1.exchange=1
tod.queueing.binding.1.routingkey=myRoutingKey

tod.queueing.consumer.1.queue=1
tod.queueing.consumer.1.handler=jnrMessageQueueHandler\://scopes.jnrMessageQueueHandling.consumeRPC

```
While in the example above the identifier for all AMQP keywords is '1', this is arbitrary. Any number could be used and a different number per keyword. 

Partial example of having multiple channels:
```
tod.queueing.connection.1.host=${AMQP_URL}
tod.queueing.connection.1.password=${AMQP_PASS}
tod.queueing.connection.1.port=5672
tod.queueing.connection.1.user=${AMQP_USER}
tod.queueing.connection.1.vhost=integrations

tod.queueing.channel.1.connection=1

tod.queueing.exchange.1.channel=1
tod.queueing.exchange.1.name=rpc

tod.queueing.channel.2.connection=1

tod.queueing.exchange.99.channel=2
tod.queueing.exchange.99.name=rpc
...
```

## Acking & Nacking
By default messages received by the plugin are automatically acknowledged. This can be changed by setting the `autoAck` property on the consumer to false.

In that case, the plugin will `ack` the message after the callback has succesfully returned. If an exception is thrown however within the callback, the message will be rejected. To `nack` a message, return `Packages.com.rabbitmq.client.AMQP.Basic.Nack` from the callback.

Other settings that influence the acknolodgement (overridding their default values):
```
tod.queueing.consumer.ack.multiple=true
tod.queueing.consumer.nack.multiple=true
tod.queueing.consumer.nack.requeue=true
tod.queueing.consumer.reject.requeue=true
```

## RPC Style
TODO

## Headless Client Pool configuration
TODO

## Supported properties

### connection 
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| user | string |  | required |
| password | string |  | required |
| host | string | localhost |  |
| port | int | 5672 |  |
| vhost  | string |  |  |
| connectiontimeout | intr |   |  |
| handshaketimeout | intr |   |  |
| shutdowntimeout | int |   |  |
| rpctimeout | int |   |  |

### channel
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| connection | int | default connection | connection reference |
| qos | int |  |  |
| qos.global | int |  |  |

### exchange
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| channel | int | default channel | channel reference |
| name | string |  |  |
| durable | boolean | false |  |
| autodelete | boolean | false |  |
| options |  |  |  |

### queue
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| channel | int | default channel | channel reference |
| name | string |  |  |
| durable | boolean | false |  |
| autodelete | boolean | false |  |
| exclusive | boolean | false |  |
| options |  |  |  |

### binding
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| queue | int | default queue | queue reference |
| exchange | int | default exchange | exchange reference |
| routingkey | string |  | required |
NOTES 
* Queue and Exchange must be on the same Channel 

### consumer
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| queue | int | default queue | queue reference |
| handler | string |  | required |
| consumertag | string |  | if not specified, the AMQP server will generate a random value |
| autoack | boolean | true |  |
| ack.multiple | boolean | false |  |
| nack.multiple | boolean | false |  |
| nack.requeue | boolean | false |  |
| reject.requeue | boolean | false |  |
| nolocal | boolean | false |  |
| exclusive | boolean | false |  |

### plugin/global
| property  | type | default | description |
| ------------- | ------------- | ------------- | ------------- |
| clientpoolsize | int | 5 |  |
| exhaustedaction | grow/block/fail | block |  |

# Feature Requests & Bugs
Found a bug or would like to see a new feature implemented? Raise an issue in the [Issue Tracker](https://github.com/TheOrangeDots/Servoy-AMQPListeners-Plugin/issues)

# Contributing
Eager to fix a bug or introduce a new feature? Clone the repository and issue a pull request

# License
The Servoy AMQPListeners plugin is licensed under MIT License

