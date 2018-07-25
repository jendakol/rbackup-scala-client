<template>
    <div id="app">
        <div>
            <p v-if="isConnected">
                We're connected to the server!<br>
                Message from server: "{{socketMessage}}"<br>
                <button @click="pingServer()">Ping Server</button>
            </p>
            <p v-else>
                Waiting for connection to the server...<br>
            </p>
        </div>
    </div>

</template>

<script>
    const WebSocket = require('isomorphic-ws');

    let APP;

    export default {
        data() {
            return {
                ws: null,
                isConnected: false,
                socketMessage: '',
                connectionCheck: null
            }
        },
        created: function () {
            APP = this;

            APP.ws = new WebSocket('ws://localhost:9000/ws-api');

            APP.connectionCheck = setInterval(function () {
                if (APP.ws.readyState === 1) {
                    APP.isConnected = true;
                    clearInterval(APP.connectionCheck)
                }
            }, 1000);

            APP.ws.onopen = function () {
                APP.isConnected = true;
            };
            APP.ws.onclose = function () {
                APP.isConnected = false;
            };
            APP.ws.onerror = function (err) {
                APP.isConnected = false;
                console.log("WS error: " + err);
            };
            APP.ws.onmessage = function (data) {
                APP.socketMessage = JSON.stringify(JSON.parse(data.data));
                console.log("WS received: " + data.data);
            };
        },
        methods: {
            pingServer() {
                APP.ws.send(JSON.stringify({name: "ping"}));
            }
        }
    }
</script>

<style scoped lang="scss">
</style>
