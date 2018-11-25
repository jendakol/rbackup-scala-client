<template>
    <div>
        SETTINGS
        {{this.settings}}

        <v-item-group multiple v-for="(settings, sectionName) in this.settings" :key="sectionName">
            <v-subheader>{{sectionName}}</v-subheader>
            <v-flex v-for="(setting, name) in settings" :key="name">
                <v-switch v-if="setting.type==='boolean'"
                          :label="name"
                          v-model="setting.value"
                          @change="saveSettings"
                ></v-switch>

                <v-container v-if="setting.type==='number'" fluid>
                    <v-flex
                            shrink
                            style="width: 60px">
                        <v-text-field
                                v-model="setting.value"
                                class="mt-0"
                                single-line
                                type="number"
                                @input="saveSettings">
                        </v-text-field>
                        <span>{{name}}</span>
                    </v-flex>
                </v-container>

                <v-container v-if="setting.type==='datetime'" fluid>
                    <v-flex md12 sm4 lg-offset8>
                        <datetime type="datetime"
                                  format="ccc d.L.y, T"
                                  placeholder="No date selected"
                                  v-model="setting.value"
                                  :minute-step="10"
                                  @input="saveSettings">
                            <label slot="before">{{name}}</label>
                            <v-tooltip bottom slot="after">
                                <v-icon large slot="activator" color="red lighten-1" @click="resetDateTime(sectionName, name)">cancel
                                </v-icon>
                                <span>Reset this setting</span>
                            </v-tooltip>
                        </datetime>
                    </v-flex>
                </v-container>
            </v-flex>
        </v-item-group>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    export default {
        name: "Settings",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {},
        created() {
            this.registerWsListener(this.receiveWs);

            let toValue = this.toValue;

            this.ajax("settingsLoad").then(response => {
                if (response.success) {
                    let newSettings = {};

                    let respData = response.data;

                    Object.keys(respData).forEach(function (key, index) {
                        let newSetting = {};
                        let settingsSection = respData[key];

                        Object.keys(settingsSection).forEach(function (key, index) {
                            newSetting[key] = {
                                value: toValue(settingsSection[key].type, settingsSection[key].value),
                                type: settingsSection[key].type
                            }
                        });

                        newSettings[key] = newSetting
                    });

                    this.settings = newSettings;
                } else {
                    this.$snotify.error("Could not load settings :-(")
                }
            })
        },
        data() {
            return {
                settings: null
            }
        },
        methods: {
            receiveWs(message) {
            },
            saveSettings() {
                let updatedSettings = {};
                let setts = this.settings;

                Object.keys(setts).forEach(function (key, index) {
                    let sett = setts[key];
                    Object.keys(sett).forEach(function (key, index) {
                        updatedSettings[key] = sett[key].value + ""
                    });
                });

                this.ajax("settingsSave", {settings: updatedSettings}).then(response => {
                    if (!response.success) {
                        this.$snotify.error("Could not save settings :-(")
                    }
                })
            },
            toValue(type, strValue) {
                switch (type) {
                    case 'number':
                    case 'boolean':
                        return eval(strValue);

                    default:
                        return strValue;
                }
            },
            resetDateTime(sectionName, name) {
                this.settings[sectionName][name].value = "";
                this.saveSettings()
            }
        }
    }
</script>

<style type="text/css">
    input.vdatetime-input {
        padding: 8px 10px;
        font-size: 16px;
        border: solid 1px #ddd;
        color: #444;
    }
</style>