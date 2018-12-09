<template>
    <v-container fluid>
        <v-layout row>
            <v-list two-line>
                <v-list-tile v-for="transfer in transfers" :key="transfer.name">
                    <v-progress-circular :value="transfer.progress">{{ transfer.progress }}</v-progress-circular>
                    &nbsp;
                    <v-list-tile-content>
                        <v-list-tile-title v-html="transfer.name"></v-list-tile-title>
                        <v-list-tile-sub-title v-html="transfer.status"></v-list-tile-sub-title>
                    </v-list-tile-content>
                </v-list-tile>
            </v-list>
        </v-layout>
    </v-container>
</template>

<script>
    export default {
        name: "TransfersStatus",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        data() {
            return {
                transfers: {}
            }
        },
        created() {
            this.registerWsListener(this.receiveWs)
        },
        methods: {
            receiveWs(message) {
                switch (message.type) {
                    case "fileTransferUpdate": {
                        this.updateProgress(message.data);
                    }
                        break;
                }
            },
            updateProgress(fileTransfer) {
                let newData = Object.assign({}, this.transfers);
                let key = this.strToHex(fileTransfer.name);

                if (fileTransfer.status === "done") {
                    delete newData[key];
                } else {
                    let progress = Math.min(99, Math.floor(fileTransfer.transferred / fileTransfer.total_size * 100)); // max. show 99%

                    let status = progress === 99
                        ? "Finishing..."
                        : (fileTransfer.speed > 1500
                            ? fileTransfer.type + " @ " + (Math.round(fileTransfer.speed / 100) / 10) + " MBps"
                            : fileTransfer.type + " @ " + (Math.round(fileTransfer.speed * 10) / 10) + " kBps");

                    newData[key] = {
                        name: fileTransfer.name,
                        progress: progress,
                        status: status
                    };
                }

                this.transfers = newData;
            },
            strToHex(str) {
                var arr1 = [];
                for (var n = 0, l = str.length; n < l; n++) {
                    var hex = Number(str.charCodeAt(n)).toString(16);
                    arr1.push(hex);
                }
                return arr1.join('');
            }
        }
    }
</script>