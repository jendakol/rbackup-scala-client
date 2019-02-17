<template>
    <v-container fluid v-if="initialized === true">
        <v-icon>fa fa-plug</v-icon>
        <span style="color: green;">Connected</span> to RBackup server (v{{ this.serverVersion }}) @ {{ this.connectedHost }} (as
        <i>{{this.deviceId}}</i>)
    </v-container>
    <v-container fluid v-else>
        Checking the connection...
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
                initialized: false,
                connectedHost: "",
                serverVersion: "",
                deviceId: "",
            }
        },
        created() {
            this.updateStatus();
        },
        methods: {
            updateStatus() {
                this.ajax("status").then(resp => {
                    if (resp.success && resp.status === "READY") {
                        this.connectedHost = resp.data.host;
                        this.serverVersion = resp.data.serverVersion;
                        this.deviceId = resp.data.deviceId;
                        this.initialized = true;
                    }
                })
            }
        }
    }
</script>