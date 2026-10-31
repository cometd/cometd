import {Transport} from "./Transport";

export class LongPollingTransport extends Transport {
    protected xhrSend(packet: {
        transport: LongPollingTransport,
        url: string,
        sync: boolean,
        headers: object,
        body: string,
        onSuccess: (response: string) => void,
        onError: (reason: string, exception?: Error) => void
    }): XMLHttpRequest;
}
