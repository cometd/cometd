import {Transport} from "./Client";

export class WebSocketTransport implements Transport {
    protected createWebSocket(url: string, protocol: string): WebSocket;
}
