<template>
    <div id="this">
        <div>
            <p v-if="isConnected">
                We're connected to the server!<br>
                Message from WS server: "{{socketMessage}}"<br>
                Message from RBackup server: "{{cloudResponseMessage}}"<br>
                <button @click="pingServer()">Ping Server</button>
                <button @click="register()">Register</button>
                <button @click="login()">Login</button>
                <!--<button @click="saveFileTree()">Send file tree</button>-->
            </p>
            <p v-else>
                Waiting for connection to the server...<br>
            </p>

        </div>

        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData" show-checkbox multiple allow-batch whole-row></v-jstree>


        <vue-context ref="menu">
            <ul>
                <li @click="uploadManually">Upload now</li>
                <li @click="alert('')">Option 2</li>
            </ul>
        </vue-context>
    </div>

</template>

<script>
    const HostUrl = "localhost:9000";

    const WebSocket = require('isomorphic-ws');

    import VJstree from 'vue-jstree';
    import axios from 'axios';
    import {VueContext} from 'vue-context';

    export default {
        components: {
            VJstree,
            VueContext
        },
        data() {
            return {
                ws: null,
                isConnected: false,
                socketMessage: '',
                cloudResponseMessage: '',
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
                rightClicked: null,
                itemEvents: {
                    contextmenu: (a, item, event) => {
                        this.rightClicked = item;
                        this.$refs.menu.open(event);
                        event.preventDefault()
                    }
                }
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
                        return t.data;
                    }).catch(error => {
                        console.log(error);
                    })
            },
            pingServer() {
                this.ajax("ping").then(t => {
                    this.cloudResponseMessage = t.serverResponse
                })
            },
            register() {
                this.ajax("register", {username: "bb22", password: "ahoj"}).then(t => {
                    if (t.success) {
                        this.cloudResponseMessage = "Account registered: " + t.account_id
                    } else {
                        this.cloudResponseMessage = "Account NOT registered, because: " + t.reason
                    }
                })
            },
            login() {
                this.ajax("login", {username: "bb22", password: "ahoj"}).then(t => {
                    if (t.success) {
                        this.cloudResponseMessage = "Login successful!"
                    } else {
                        this.cloudResponseMessage = "Login was NOT successful"
                    }
                })
            },
            uploadManually() {
                let path = this.rightClicked.value;

                // alert("Uploading " + path);

                this.ajax("uploadManually", {path: path}).then(t => {
                    if (t.success) {
                        this.cloudResponseMessage = "Upload successful!"
                    } else {
                        this.cloudResponseMessage = "Upload NOT successful, because: " + t.reason
                    }
                })
            },
            saveFileTree() {
                this.ajax("saveFileTree", this.fileTreeData)
            },
        },
    }
</script>

<style scoped lang="scss">
</style>
