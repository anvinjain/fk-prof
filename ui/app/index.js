import React from 'react';
import ReactDOM from 'react-dom';
import {
  Route,
  Router,
  IndexRedirect,
  browserHistory,
} from 'react-router';
import { Provider } from 'react-redux';

import 'react-select/dist/react-select.css';
import 'react-datetime/css/react-datetime.css';
import "./assets/styles/mdl/material.min.css";
import "./assets/styles/mdl/material.min";

import store from './store';

import Root from 'components/RootComponent';
import App from 'components/AppComponent';
import CPUSampling from 'components/CPUSamplingComponent';
import AggregatedProfileDataContainer from 'components/AggregatedProfileDataContainer';
import Policy from "components/PolicyComponent";

import './assets/styles/global.css';

const routes = (
  <Route path="/" component={Root}>
    <IndexRedirect to="/profiler" />
    <Route path="/profiler" component={App}>
      <Route path="/profiler/profile-data/:traceName" component={AggregatedProfileDataContainer} />
      <Route path="/profiler/policy" component={Policy}/>
    </Route>
    <Route path="/work-type/cpu_sample_work/:traceName" component={CPUSampling} />
  </Route>
);

ReactDOM.render(
  <Provider store={store}>
    <Router history={browserHistory}>
      {routes}
    </Router>
  </Provider>,
  document.getElementById('root'),
);
