# Cranker

Avoid orchestration by reversing the polairty of your infrastructure.

## todo

* websockets over websockets
 * seems like we need some framing
 * but the client websocket could be tied to a cranker websocket
* ability to use one cranker-lb for multiple services
 * when approx connects to lb have it send some service identification
 * and then cranker lb should keep a websocket pool per-service id
 * load balancers could send traffic to that with http headers or paths
  * if it was a header then the approx could use the same header
    * when approx makes the websocket send X-Service-Id: my-service
    * configure the loadbalancer to send to cranker-ln with X-Service-Id: my-service
* weights
 * have cranker approx connect to multiple cranker lbs
 * but associate them with a weight
 * so if cranker approx knows lb xxx is in another datacenter it can cost it more
 * the cost is sent with the approx websocket creation
 * so now cranker lb knows what cost to apply
* need to test sending a bunch of data
* the load balancer side and the app server side need to start independently
 * we need to be able to turn the test mode on
 * and select the mode: test, loadbal or appserver
* the app server side needs to wait
* implement HTTP proxying better
 * adding proxy header in correct circumstances
 
## where we are right now

We have both ends of cranker coded

* lb-server and cranker-server implement the load balancer end
 * start-lb starts both the servers
 * both servers need an address but default to
  * 8000 - cranker-server (websockets)
  * 8001 - load balancer proxy
* cranker-connector implements the app server side
 * it needs the app-server address ...
 * ... and the address of the cranker server
* we have a test mode that:
 * sets up a fake appserv on 8003
 * sets up cranker-connector from the appserv to a load balancer
 * fires a request at the load balancer
 * shows that the request comes back via the fake appserv
 
## how it works

HTTP requests with cranker:

![cranker for http](cranker-http-request.png)

* a single cranker/a runs near your load balancer, where it can have a fixed address
 * it listens to 2 sockets, x and y
 * receives connections from the load balancer on x
 * receives websocket connections from cranker/b on y
  * these will arrive in lumps
  * when a cranker-connector starts it tries to connect a lot of sockets
* cranker/b runs near your app server
 * makes websockets to cranker/a
 * what comes over the websocket is encoded HTTP requests from the load balancer
 * proxy the request to the app server
 * return the response encoded over the websocket


Websockets with cranker:

![cranker for websockets](cranker-websockets.png)

## Installation

Download from http://example.com/FIXME.


## Usage

FIXME: explanation

    $ java -jar cranker-0.1.0-standalone.jar [args]


## Examples

...

### Bugs

...


## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
