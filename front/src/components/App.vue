<template>
    <div id="this">
        <div>
            <p v-if="isConnected">
                We're connected to the server!<br>
                Message from server: "{{socketMessage}}"<br>
                <button @click="pingServer()">Ping Server</button>
                <button @click="saveFileTree()">Send file tree</button>
            </p>
            <p v-else>
                Waiting for connection to the server...<br>
            </p>
        </div>

        <v-jstree :data="fileTreeData" :async="loadData" show-checkbox multiple allow-batch whole-row></v-jstree>
    </div>

</template>

<script>
    const HostUrl = "localhost:9000";

    const WebSocket = require('isomorphic-ws');

    import VJstree from 'vue-jstree';
    import axios from 'axios';

    export default {
        components: {
            VJstree
        },
        data() {
            return {
                ws: null,
                isConnected: false,
                socketMessage: '',
                connectionCheck: null,
                fileTreeData: [],
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    // axios.post('http://localhost:9000/ajax-api', {name: "dirList", data: {path: path != undefined ? path + "" : ""}})
                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response)
                        })

                },
            }
        },
        created: function () {
            this.ws = new WebSocket("ws://" + HostUrl + "/ws-api");

            this.connectionCheck = setInterval(() => {
                if (this.ws.readyState === 1) {
                    this.isConnected = true;
                    clearInterval(this.connectionCheck)
                }
            }, 1000);

            this.ws.onopen = () => {
                this.isConnected = true;
            };
            this.ws.onclose = () => {
                this.isConnected = false;
            };
            this.ws.onerror = (err) => {
                this.isConnected = false;
                console.log("WS error: " + err);
            };
            this.ws.onmessage = (data) => {
                this.socketMessage = JSON.stringify(JSON.parse(data.data));
            };
        },
        methods: {
            ajax(name, data) {
                return axios.post("http://" + HostUrl + "/ajax-api", {name: name, data: data})
                    .then(t => {
                        return JSON.parse(t.data)
                    }).catch(error => {
                        console.log(error);
                    })
            },
            pingServer() {
                this.ws.send(JSON.stringify({name: "ping"}));
            },
            saveFileTree() {
                this.ajax("saveFileTree", this.fileTreeData)
            }
        }
    }
</script>

<style scoped lang="scss">
</style>
