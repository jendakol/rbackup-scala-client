<template>
    <div>
        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData"></v-jstree>

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
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    import BottomBar from '../components/BottomBar.vue';

    export default {
        name: "BackupManual",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            VJstree,
            VueContext,
        },
        data() {
            return {
                fileTreeData: [],
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response)
                        })

                },
                rightClicked: null,
                itemEvents: {
                    contextmenu: (a, item, event) => {
                        this.rightClicked = item;

                        if (item.isFile) {
                            this.$refs.fileMenu.open(event);
                        } else if (item.isDir) {
                            this.$refs.dirMenu.open(event);
                        } else console.log("It's weird - not version nor dir nor file");

                        event.preventDefault()
                    }
                }
            }
        }, methods: {
            uploadManually() {
                let path = this.rightClicked.value;

                this.$snotify.success("File is being uploaded", {timeout: 1000});
                this.ajax("upload", {path: path});
            },
        }
    }
</script>