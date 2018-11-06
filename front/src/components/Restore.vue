<template>
    <div>

        <div v-if="fileTreeData.length === 0">
            <v-container text-xs-center>
                <v-layout row wrap>
                    <v-flex>
                        <v-card app>
                            <v-card-text class="px-0">
                                <v-icon>info</v-icon>
                                No items yet, backup something first ;-)

                                {{ fileTreeData }}
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
            </ul>
        </vue-context>
        <vue-context ref="fileMenu">
            <ul>
                <li @click="restoreLast">Restore to last version</li>
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
            restoreVersion(version) {
                let path = version.path;
                let versionId = version.versionId;

                this.ajax("download", {
                    path: path,
                    version_id: versionId
                });
            },
            restoreLast() {
                let versions = this.rightClicked.children;
                let last = versions[versions.length - 1];

                this.restoreVersion(last)
            },
            receiveWs(message) {
                switch (message.type) {
                    case "backedUpFilesUpdate": {
                        this.fileTreeData = message.data
                    }
                        break;
                }
            },
            selectTreeNode(path) {
                return JSPath.apply("..{.value === '" + path + "'}", this.fileTreeData)[0]
            },
        }
    }
</script>