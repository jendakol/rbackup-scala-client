<template>
    <div id="this">
        <div>
            <p v-if="isConnected">
                We're connected to the server!<br>
                Message from WS server: "{{socketMessage}}"<br>
                Message from RBackup server: "{{cloudResponseMessage}}"<br>
                <button @click="register()">Register</button>
                <button @click="login()">Login</button>
                <!--<button @click="saveFileTree()">Send file tree</button>-->
            </p>
            <p v-else>
                Waiting for connection to the server...<br>
            </p>

        </div>

        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData" show-checkbox multiple allow-batch whole-row></v-jstree>


        <vue-context ref="fileMenu">
            <ul>
                <li @click="uploadManually">Upload file now</li>
            </ul>
        </vue-context>
        <vue-context ref="dirMenu">
            <ul>
                <li @click="uploadManually">Upload dir now</li>
            </ul>
        </vue-context>
        <vue-context ref="versionMenu">
            <ul>
                <li @click="restore">Restore this version</li>
            </ul>
        </vue-context>

        <vue-snotify></vue-snotify>
    </div>

</template>

<script>
    const HostUrl = "localhost:9000";

    const WebSocket = require('isomorphic-ws');

    import VJstree from 'vue-jstree';
    import axios from 'axios';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

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

                        if (item.isVersion) {
                            this.$refs.versionMenu.open(event);
                        } else if (item.isFile) {
                            this.$refs.fileMenu.open(event);
                        } else if (item.isDir) {
                            this.$refs.dirMenu.open(event);
                        } else console.log("It's weird - not version nor dir nor file");

                        event.preventDefault()
                    }
                }
            }
        },
        created: function () {
            this.initWs();

            this.ws.onopen = () => {
                this.isConnected = true;
            };
            this.ws.onclose = () => {
                this.isConnected = false;
                this.initWs();
            };
            this.ws.onerror = (err) => {
                this.isConnected = false;
                console.log("WS error: " + err);
                this.initWs();
            };
            this.ws.onmessage = (data) => {
                this.receiveWs(JSON.parse(data.data));
            };
        },
        methods: {
            initWs() {
                this.ws = new WebSocket("ws://" + HostUrl + "/ws-api");

                this.connectionCheck = setInterval(() => {
                    if (this.ws.readyState === 1) {
                        this.isConnected = true;
                        this.$snotify.success("Connection to client was successful", {timeout: 1500});
                        clearInterval(this.connectionCheck);
                        this.ws.send(JSON.stringify({name: "init"}));
                    }
                }, 1000);
            },
            ajax(name, data) {
                return axios.post("http://" + HostUrl + "/ajax-api", {name: name, data: data})
                    .then(t => {
                        return t.data;
                    }).catch(error => {
                        console.log(error);
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
            asyncActionWithNotification(actionName, data, initialText, responseToPromise) {
                this.$snotify.async(initialText, () => new Promise((resolve, reject) => {
                    this.ajax(actionName, data).then(resp => {
                        responseToPromise(resp)
                            .then(text => {
                                resolve({
                                    body: text,
                                    config: {
                                        html: text,
                                        closeOnClick: true,
                                        timeout: 3500
                                    }
                                })
                            }, errText => reject({
                                body: errText,
                                config: {
                                    // TODO HTML formatting
                                    // html: '<div class="snotifyToast__title"><b>Html Bold Title</b></div><div class="snotifyToast__body"><i>Html</i> <b>toast</b> <u>content</u></div>',
                                    closeOnClick: true,
                                    timeout: 3500
                                }
                            }))
                    })
                }));
            },
            uploadManually() {
                let path = this.rightClicked.value;

                this.asyncActionWithNotification("uploadManually", {path: path}, "Manually uploading " + path, (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        success("Manual upload of " + path + " successful!")
                    } else {
                        error("Upload of " + path + " was NOT successful, because " + resp.reason)
                    }
                }));
            },
            restore() {
                let path = this.rightClicked.path;
                let versionId = this.rightClicked.versionId;
                let versionDateTime = this.rightClicked.text;

                this.asyncActionWithNotification("download", {
                    path: path,
                    version_id: versionId
                }, "Restoring " + path + " to " + versionDateTime, (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        success("File " + path + " successfully restored to " + versionDateTime + "!")
                    } else {
                        error(resp.message)
                    }
                }));
            },
            receiveWs(message) {
                switch (message.type) {
                    case "fileTreeUpdate": {
                        let data = message.data;
                        let node = this.selectTreeNode(data.path);

                        if (node != undefined) {
                            node.children = data.versions;
                            node.isLeaf = false;
                        }
                    }
                        break;
                }
            },
            selectTreeNode(path) {
                return JSPath.apply("..{.value === '" + path + "'}", this.fileTreeData)[0]
            },
        },
    }
</script>

<style scoped lang="scss">
</style>
