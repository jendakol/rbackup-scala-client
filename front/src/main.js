import Vue from 'vue';
import App from './components/App.vue';
import Vuetify from 'vuetify'
import '../sass/style.scss';
import Snotify, { SnotifyPosition } from 'vue-snotify/vue-snotify.min.js'
import Datetime from 'vue-datetime'

const options = {
    toast: {
        timeout: 3500,
        position: SnotifyPosition.rightTop,
        showProgressBar: false,
        pauseOnHover: true,
        closeOnClick: true
    }
};

Vue.use(Snotify, options);
Vue.use(Datetime, options);

import 'vue-datetime/dist/vue-datetime.css'
import 'vuetify/dist/vuetify.min.css'
Vue.use(Vuetify, {
    theme: {
        "primary": "#0d47a1",
        "secondary": "#424242",
        "accent": "#64b5f6",
        "error": "#FF5252",
        "info": "#01579b",
        "success": "#4CAF50",
        "warning": "#ff5722"
    }
});

new Vue(App).$mount('#app');
