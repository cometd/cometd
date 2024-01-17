/**
 * Client-side extension that converts binary JavaScript objects
 * (ArrayBuffer, DataView and TypedArrays) into a textual
 * representation suitable for JSON, using the Z85 algorithm.
 */
export function BinaryExtension() {
  this.incoming = (message) => {
    if (!/^\/meta\//.test(message.channel)) {
      const ext = message.ext;
      if (ext) {
        const binaryExt = ext.binary;
        if (binaryExt) {
          message.data.data = cometdModule.Z85.decode(message.data.data);
        }
      }
    }
    return message;
  };

  this.outgoing = (message) => {
    if (!/^\/meta\//.test(message.channel)) {
      const ext = message.ext;
      if (ext) {
        const binaryExt = ext.binary;
        if (binaryExt) {
          message.data.data = cometdModule.Z85.encode(message.data.data);
        }
      }
    }
    return message;
  };
}
