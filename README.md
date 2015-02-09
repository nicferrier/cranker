# putsh

avoid orchestration by reversing the polairty of your infrastructure.

## todo

* putsh-connect needs to connect multiple sockets
 * a configurable number
 * how do we test that??
  * that more than one socket is being used?


## where we are right now

we have both ends of putsh coded

* putsh-connect connects a client websockets to putsh-server
* lb is a load balancer wrapper, something to get requests from a downstream lb
* lb-http-request is a dummy function to send http requests to lb
* appserv-handler is a fake app serv
* we have a test mode that:
 * sets up the fake appserv
 * sets up putsh from the appserv to a load balancer
 * fires a request at the load balancer
 * shows that the request comes back via the fake appserv
 
## how it works

* putsh/a
 * listen to 2 sockets, x and y
 * receive connections from the load balancer on x
  * these will arrive as and when, probably not in one go
 * receive connections from putsh/b on y
  * these will arrive in lumps
 * we need to keep the putsh/b sockets open 
  * and send data back through them
  * when data arrives from the load balancer
* putsh/b
 * make connections in 2 different directions, o and p
 * start by making 10 connections to o
 * o is presumed to be a putsh/a
 * when data arrives on one of the connections
  * make a connection to p
  * send the data there
 * p is presumed to be a backend server


## Installation

Download from http://example.com/FIXME.

## Usage

FIXME: explanation

    $ java -jar putsh-0.1.0-standalone.jar [args]

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
