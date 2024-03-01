/**
 * This client-side extension enables the client to acknowledge to the server
 * the messages that the client has received.
 * For the acknowledgement to work, the server must be configured with the
 * correspondent server-side ack extension. If both client and server support
 * the ack extension, then the ack functionality will take place automatically.
 * By enabling this extension, all messages arriving from the server will arrive
 * via /meta/connect, so the comet communication will be slightly chattier.
 * The fact that all messages will return via /meta/connect means also that the
 * messages will arrive with total order, which is not guaranteed if messages
 * can arrive via both /meta/connect and normal response.
 * Messages are not acknowledged one by one, but instead a batch of messages is
 * acknowledged when the /meta/connect returns.
 */
export function AckExtension() {
  let _cometd;
  let _serverSupportsAcks = false;
  let _batch;

  function _debug(text, args) {
    _cometd._debug(text, args);
  }

  this.registered = (name, cometd) => {
    _cometd = cometd;
    _debug("AckExtension: executing registration callback");
  };

  this.unregistered = () => {
    _debug("AckExtension: executing unregistration callback");
    _cometd = null;
  };

  this.incoming = (message) => {
    const channel = message.channel;
    const ext = message.ext;
    if (channel === "/meta/handshake") {
      if (ext) {
        const ackField = ext.ack;
        if (typeof ackField === "object") {
          // New format.
          _serverSupportsAcks = ackField.enabled === true;
          const batch = ackField.batch;
          if (typeof batch === "number") {
            _batch = batch;
          }
        } else {
          // Old format.
          _serverSupportsAcks = ackField === true;
        }
      }
      _debug(
        "AckExtension: server supports acknowledgements",
        _serverSupportsAcks
      );
    } else if (
      channel === "/meta/connect" &&
      message.successful &&
      _serverSupportsAcks
    ) {
      if (ext && typeof ext.ack === "number") {
        _batch = ext.ack;
        _debug("AckExtension: server sent batch", _batch);
      }
    }
    return message;
  };

  this.outgoing = (message) => {
    const channel = message.channel;
    if (!message.ext) {
      message.ext = {};
    }
    if (channel === "/meta/handshake") {
      message.ext.ack = _cometd && _cometd.ackEnabled !== false;
      _serverSupportsAcks = false;
      _batch = 0;
    } else if (channel === "/meta/connect") {
      if (_serverSupportsAcks) {
        message.ext.ack = _batch;
        _debug("AckExtension: client sending batch", _batch);
      }
    }
    return message;
  };
}
