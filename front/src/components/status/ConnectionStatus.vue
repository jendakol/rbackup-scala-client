<template>
    <v-container fluid>
        <v-icon>fa fa-plug</v-icon>
        <span style="color: green;">Connected</span> to RBackup server @ {{ this.connectedHost }}
    </v-container>
</template>

<script>
    export default {
        name: "ConnectionStatus",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        data() {
            return {
                connectedHost: ""
            }
        },
        created() {
            this.registerWsListener(this.receiveWs);
            this.updateStatus();
        },
        methods: {
            receiveWs(message) {
                // switch (message.type) {
                //     case "fileUploadUpdate": {
                //         alert(message.data);
                //     }
                //         break;
                // }
            },
            updateStatus() {
                this.ajax("status").then(resp => {
                    if (resp.success && resp.status === "READY") {
                        this.connectedHost = resp.data.host;
                    }
                })
            }
        }
    }
</script>