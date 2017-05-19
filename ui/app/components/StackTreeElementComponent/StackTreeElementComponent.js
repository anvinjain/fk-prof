import React, { Component } from 'react';
import styles from './StackTreeElementComponent.css';

// //Reference: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Object/is
// if (!Object.is) {
//   Object.is = function(x, y) {
//     // SameValue algorithm
//     if (x === y) { // Steps 1-5, 7-10
//       // Steps 6.b-6.e: +0 != -0
//       return x !== 0 || 1 / x === 1 / y;
//     } else {
//      // Step 6.a: NaN == NaN
//      return x !== x && y !== y;
//     }
//   };
// }

class StackTreeElementComponent extends Component {
  constructor (props) {
    super(props);
    this.state = {
    };
  }
  
  shouldComponentUpdate(nextProps, nextState) {
    //this.props.style can change because it is generated by react-virtualized
    if(
      (this.props.nodestate !== nextProps.nodestate) || 
      (this.props.highlight !== nextProps.highlight) || 
      (this.props.listIdx !== nextProps.listIdx)
      //(!Object.is(this.props.style, nextProps.style))
      ) {
        if(this.props.stackline === "java.net.SocketOutputStream.socketWrite0") {
          console.log("yolo");
        }
      return true;
    }
    return false;
  }

  getStyleAndIconForNode() {
    if (this.props.nodestate) {
      return [styles.collapsedIcon, "play_arrow"];
    } else {
      return ["mdl-color-text--accent", "play_arrow"];
    }
  }

  getIconForHighlight() {
    return this.props.highlight ? "highlight" : "lightbulb_outline";
  }

  render () {
    if(this.props.stackline === "java.net.SocketOutputStream.socketWrite0") {
      console.log("rofl");
    }
    let leftPadding = (this.props.indent || 0) + "px";
    return (
      <div className={this.props.highlight ? styles.highlight : this.props.subdued && styles.subdued} style={this.props.style}>
        <div>
          <div className={styles.stackline} style={{marginLeft: leftPadding}} title={this.props.nodename}>
            <span className={`material-icons mdl-color-text--primary ${styles.nodeIcon}`} onClick={this.props.onHighlight}>
              {this.getIconForHighlight()}
            </span>
            <span className={styles.stacklineText} onClick={this.props.onClick}>
              <span className={`material-icons ${this.getStyleAndIconForNode()[0]} ${styles.nodeIcon}`}>
                {this.getStyleAndIconForNode()[1]}
              </span>
              <span>{this.props.stackline}</span>
            </span>
          </div>
        </div>
      </div>
    );
  }
}

export default StackTreeElementComponent;



      /*<tr className={this.props.highlight ? styles.highlight : this.props.subdued && styles.subdued}>
        <td>
          {this.props.samples ? (
            <div className={`${styles.pill} mdl-color-text--primary`}>
              <div className={styles.number}>{this.props.samples}</div>
              <div className={styles.percentage}>
                <div className={styles.shade} style={{ width: `${this.props.samplesPct}%` }} />
                {this.props.samplesPct}%
              </div>
            </div>
          ) : <div>&nbsp;</div>}
        </td>
        <td>
          <div className={styles.stackline} style={{marginLeft: leftPadding}} title={this.props.nodename}>
            <span className={`material-icons mdl-color-text--primary ${styles.nodeIcon}`} onClick={this.props.onHighlight}>
              {this.getIconForHighlight()}
            </span>
            <span className={styles.stacklineText} onClick={this.props.onClick}>
              <span className={`material-icons ${this.getStyleAndIconForNode()[0]} ${styles.nodeIcon}`}>
                {this.getStyleAndIconForNode()[1]}
              </span>
              <span>{this.props.stackline}</span>
            </span>
          </div>
        </td>
      </tr>*/