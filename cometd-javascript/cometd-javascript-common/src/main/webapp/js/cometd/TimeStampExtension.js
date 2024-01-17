// The timestamp extension adds the optional timestamp field to all outgoing messages.
export function TimeStampExtension() {
  this.outgoing = (message) => {
    message.timestamp = new Date().toUTCString();
    return message;
  };
}
