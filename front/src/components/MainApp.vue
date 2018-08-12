<template>
    <div id="this">
        <b-navbar toggleable="md" type="dark" variant="dark">

            <b-navbar-toggle target="nav_collapse"></b-navbar-toggle>

            <b-navbar-brand href="#">RBackup</b-navbar-brand>

            <b-collapse is-nav id="nav_collapse">

                <!--<b-navbar-nav>-->
                <!--<b-nav-item href="#">Link</b-nav-item>-->
                <!--<b-nav-item href="#" disabled>Disabled</b-nav-item>-->
                <!--</b-navbar-nav>-->

                <!-- Right aligned nav items -->
                <b-navbar-nav class="ml-auto">

                    <b-nav-item-dropdown right>
                        <template slot="button-content">
                            <em>Account</em>
                        </template>
                        <b-dropdown-item href="#" @click="logout">Logout</b-dropdown-item>
                    </b-nav-item-dropdown>
                </b-navbar-nav>

            </b-collapse>
        </b-navbar>

        <div>
            <div>
                <p v-if="isConnected">
                    <b-tabs class="tabs">
                        <b-tab active>
                            <template slot="title">
                                <span class="tabTitle">
                                <i class="fas fa-info"></i>Status</span>
                            </template>

                            <div class="tabContent">
                                <Status :ajax="this.ajax" :asyncActionWithNotification="this.asyncActionWithNotification"/>
                            </div>
                        </b-tab>
                        <b-tab>
                            <template slot="title">
                                <span class="tabTitle">
                                <i class="fas fa-upload"></i>Backup</span>
                            </template>

                            <div class="tabContent">
                                <Backup :ajax="this.ajax" :asyncActionWithNotification="this.asyncActionWithNotification"/>
                            </div>
                        </b-tab>
                        <b-tab>
                            <template slot="title">
                                <span class="tabTitle">
                                <i class="fas fa-undo"></i>Restore</span>
                            </template>

                            <div class="tabContent">
                                <Restore :ajax="this.ajax" :asyncActionWithNotification="this.asyncActionWithNotification"/>
                            </div>
                        </b-tab>
                    </b-tabs>
                </p>
                <p v-else>
                    Waiting for connection to the server...<br>
                </p>

            </div>

        </div>
    </div>
</template>

<script>
    const WebSocket = require('isomorphic-ws');

    import axios from 'axios';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    import Status from '../components/Status.vue';
    import Backup from '../components/Backup.vue';
    import Restore from '../components/Restore.vue';

    export default {
        name: "MainApp",
        components: {
            VueContext,
            Status,
            Backup,
            Restore
        },
        props: {
            hostUrl: String,
            ajax: Function,
            asyncActionWithNotification: Function
        },
        data() {
            return {
                ws: null,
                isConnected: false,
                connectionCheck: null,
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
                this.ws = new WebSocket("ws://" + this.hostUrl + "/ws-api");

                this.connectionCheck = setInterval(() => {
                    if (this.ws.readyState === 1) {
                        this.isConnected = true;
                        this.$snotify.success("Connection to client backend was successful", {timeout: 1500});
                        clearInterval(this.connectionCheck);
                        this.ws.send(JSON.stringify({name: "init"}));
                    }
                }, 1000);
            },
            logout() {
                this.asyncActionWithNotification("logout", {}, "Logging out", (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        this.$emit("logout");
                        success("Logged out")
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
    span.tabTitle {
        color: black !important;
    }

    span.tabTitle .fas {
        margin-right: 5px;
    }

    .tabs {
        margin: 5px;
    }

    .tabContent {
        padding: 10px;
    }
</style>