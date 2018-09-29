<template>
    <v-app>
        <v-navigation-drawer app dark>
            <v-toolbar flat>
                <v-list>
                    <v-list-tile>
                        <v-list-tile-title class="title">
                            RBackup
                        </v-list-tile-title>
                    </v-list-tile>
                </v-list>

                <v-menu>
                    <v-btn
                            slot="activator"
                            color="secondary"
                            app>
                        <v-icon>account_circle</v-icon>
                    </v-btn>
                    <v-list>
                        <v-list-tile
                                v-for="(item, index) in accountMenu"
                                :key="index"
                                @click="item.action">
                            <v-list-tile-title>{{ item.title }}</v-list-tile-title>
                        </v-list-tile>
                    </v-list>
                </v-menu>
            </v-toolbar>

            <v-list dense class="pt-0">
                <v-list-tile
                        v-for="item in drawerMenu"
                        :key="item.title"
                        @click="item.action">
                    <v-list-tile-action>
                        <v-icon>{{ item.icon }}</v-icon>
                    </v-list-tile-action>

                    <v-list-tile-content>
                        <v-list-tile-title>{{ item.title }}</v-list-tile-title>
                    </v-list-tile-content>
                </v-list-tile>
            </v-list>
        </v-navigation-drawer>

        <v-content>
            <div class="content">
                <div>
                    <div>
                        <p v-if="isConnected">
                            <Status v-if="visiblePage === 'status'" :ajax="this.ajax" :registerWsListener="this.addWsListener"
                                    :asyncActionWithNotification="this.asyncActionWithNotification"/>

                            <Backup v-if="visiblePage === 'backup'" :ajax="this.ajax" :registerWsListener="this.addWsListener"
                                    :asyncActionWithNotification="this.asyncActionWithNotification"/>

                            <Restore v-if="visiblePage === 'restore'" :ajax="this.ajax" :registerWsListener="this.addWsListener"
                                     :asyncActionWithNotification="this.asyncActionWithNotification"/>
                        </p>
                        <p v-else>
                            Waiting for connection to the server...<br>
                        </p>

                    </div>
                </div>
            </div>
        </v-content>
    </v-app>
</template>

<script>
    const WebSocket = require('isomorphic-ws');

    import axios from 'axios';
    import {VueContext} from 'vue-context';

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
                wsListeners: Array(),
                visiblePage: "status",
                drawerMenu: [
                    {
                        title: 'Status', icon: 'dashboard', action: () => {
                            this.visiblePage = "status"
                        }
                    },
                    {
                        title: 'Backup', icon: 'cloud_upload', action: () => {
                            this.visiblePage = "backup"
                        }
                    },
                    {
                        title: 'Restore', icon: 'cloud_download', action: () => {
                            this.visiblePage = "restore"
                        }
                    }
                ],
                accountMenu: [
                    {
                        title: 'Logout', icon: 'dashboard', action: () => {
                            this.logout()
                        }
                    },
                ]
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
            addWsListener(listener) {
                this.wsListeners.push(listener)
            },
            receiveWs(message) {
                this.wsListeners.forEach((listener) => {
                    listener(message)
                })
            },
        },
    }
</script>

<style scoped lang="scss">
    .content {
        padding: 10px;
    }
</style>