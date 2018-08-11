import Vue from 'vue';
import App from './components/App.vue';
import '../sass/style.scss';
import Snotify, { SnotifyPosition } from 'vue-snotify'

const options = {
    toast: {
        timeout: 3500,
        position: SnotifyPosition.rightTop,
        showProgressBar: false,
        pauseOnHover: true
    }
};

Vue.use(Snotify, options);

new Vue(App).$mount('#app');
