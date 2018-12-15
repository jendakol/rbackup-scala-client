<template>
    <div>

        <div v-if="loaded === true && fileTreeData.length === 0">
            <v-container text-xs-center>
                <v-layout row wrap>
                    <v-flex>
                        <v-card app>
                            <v-card-text class="px-0">
                                <v-icon>info</v-icon>
                                No items yet, backup something first ;-)
                            </v-card-text>
                        </v-card>
                    </v-flex>
                </v-layout>
            </v-container>
        </div>
        <div v-else>
            <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData"></v-jstree>
        </div>

        <vue-context ref="versionMenu">
            <ul>
                <li @click="restoreVersion(rightClicked)">Restore this version</li>
                <li @click="removeVersion(rightClicked)">Delete this version</li>
            </ul>
        </vue-context>
        <vue-context ref="fileMenu">
            <ul>
                <li @click="restoreLast(rightClicked)">Restore to last version</li>
                <li @click="removeFile(rightClicked)">Delete all backed-up versions</li>
            </ul>
        </vue-context>
        <vue-context ref="dirMenu">
            <ul>
                <li @click="alert('???')">Restore all files in this directory to last version</li>
            </ul>
        </vue-context>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    export default {
        name: "Restore",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            VJstree,
            VueContext
        },
        created() {
            this.registerWsListener(this.receiveWs);
            this.loadData(null, (items) => {
                this.fileTreeData = items;
            })
        },
        data() {
            return {
                fileTreeData: [],
                loaded: false,
                loadData: (oriNode, resolve) => {
                    this.ajax("backedUpFileList", {})
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
                        }

                        event.preventDefault()
                    }
                }
            }
        },
        methods: {
            // TODO display more info about versions
            restoreVersion(version) {
                let path = version.path;
                let versionId = version.versionId;

                this.ajax("download", {
                    path: path,
                    versionId: versionId
                });
            },
            restoreLast(file) {
                let versions = file.children;
                let last = versions[versions.length - 1];

                this.restoreVersion(last)
            },
            removeVersion(version) {
                let path = version.path;
                let versionId = version.versionId;

                this.$confirm("Do you really want to delete version " + version.text + " of " + path + "?", {title: 'Warning'}).then(res => {
                    if (res) {
                        this.asyncActionWithNotification("removeRemoteFileVersion", {
                            path: path,
                            versionId: versionId
                        }, "Removing backed-up version", (resp) => new Promise((success, error) => {
                            if (resp.success === true) {
                                success("Version " + version.text + " of " + version.path + " removed!");
                            } else if (resp.success === false) {
                                error("Could not remove file version")
                            } else {
                                error("Could not remove file version: " + resp.error)
                            }
                        }));
                    }
                })
            },
            removeFile(file) {
                let path = file.value;

                this.$confirm("Do you really want to delete all backed-up versions of " + path + "?", {title: 'Warning'}).then(res => {
                    if (res) {
                        this.asyncActionWithNotification("removeRemoteFile", {
                            path: path
                        }, "Removing file", (resp) => new Promise((success, error) => {
                            if (resp.success === true) {
                                success("File " + path + " was completely removed!");
                            } else if (resp.success === false) {
                                error("Could not remove file")
                            } else {
                                error("Could not remove file: " + resp.error)
                            }
                        }));
                    }
                });
            },
            receiveWs(message) {
                switch (message.type) {
                    case "backedUpFilesUpdate": {
                        let opened = JSPath.apply('..{.opened === true}', this.fileTreeData);

                        this.fileTreeData = message.data;

                        opened.forEach(opened => {
                            JSPath.apply('..{.value === "' + opened.value + '"}', this.fileTreeData)[0].opened = true;
                        })
                    }
                        break;
                }
            },
        }
    }
</script>