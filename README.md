# putsh

avoid orchestration.

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

## how it works

Channel defines the following contract:

* open?: Returns true iff channel is open.
* close: Closes the channel. Idempotent: returns true if the channel
  was actually closed, or false if it was already closed.
* websocket?: Returns true iff channel is a WebSocket.
* send!: Sends data to client and returns true if the data was
  successfully written to the output queue, or false if the channel is
  closed. Normally, checking the returned value is not needed. This
  function returns immediately (does not block).
* Data is sent directly to the client, NO RING MIDDLEWARE IS APPLIED.
* Data form: {:headers _ :status _ :body _} or just body. Note that
  :headers and :status will be stripped for WebSockets and for HTTP
  streaming responses after the first.
* on-receive: Sets handler (fn [message-string || byte[]) for
  notification of client WebSocket messages. Message ordering is
  guaranteed by server.
* on-close: Sets handler (fn [status]) for notification of channel
  being closed by the server or client. Handler will be invoked at
  most once. Useful for clean-up. 
 * Status can be `:normal`, `:going-away`, `:protocol-error`,
  `:unsupported`, `:unknown`, `:server-close`, `:client-close`


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
