<template>
    <div>
        STATUS
    </div>
</template>

<script>
    export default {
        name: "Status",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        created() {
            this.registerWsListener(this.receiveWs)
        },
        methods: {
            receiveWs(message) {
                switch (message.type) {
                    case "fileUploadUpdate": {
                        alert(message.data);
                    }
                        break;
                }
            },
        }
    }
</script>