<template>
    <v-container fluid>
        <v-layout row>
            <v-list two-line>
                <v-list-tile v-for="uploading in uploadings" :key="uploading.name">
                    <v-progress-circular :value="uploading.progress">{{ uploading.progress }}</v-progress-circular>
                    &nbsp;
                    <v-list-tile-content>
                        <v-list-tile-title v-html="uploading.name"></v-list-tile-title>
                        <v-list-tile-sub-title v-html="uploading.status"></v-list-tile-sub-title>
                    </v-list-tile-content>
                </v-list-tile>
            </v-list>
        </v-layout>
    </v-container>
</template>

<script>
    export default {
        name: "UploadingsStatus",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        data() {
            return {
                uploadings: {}
            }
        },
        created() {
            this.registerWsListener(this.receiveWs)
        },
        methods: {
            receiveWs(message) {
                switch (message.type) {
                    case "fileUploadUpdate": {
                        this.updateProgress(message.data);
                    }
                        break;
                }
            },
            updateProgress(fileUploading) {
                let newData = Object.assign({}, this.uploadings);
                let key = this.strToHex(fileUploading.name);

                if (fileUploading.status === "done") {
                    delete newData[key];
                } else {
                    let progress = Math.min(99, Math.floor(fileUploading.uploaded / fileUploading.total_size * 100)); // max. show 99%

                    if (progress === 99) {
                        var status = "Finishing..."
                    } else {
                        if (fileUploading.speed > 1500) {
                            var status = "@ " + (Math.round(fileUploading.speed / 100) / 10) + " MBps"
                        } else {
                            var status = "@ " + fileUploading.speed + " kBps"
                        }
                    }

                    newData[key] = {
                        name: fileUploading.name,
                        progress: progress,
                        status: status
                    };
                }

                this.uploadings = newData;
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