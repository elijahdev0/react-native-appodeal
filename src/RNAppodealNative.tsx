/**
 * Appodeal Native Ad Component
 *
 * Renders a platform native-ad template (newsFeed / appWall / contentStream / gridCard)
 * bound to an ad id returned from Appodeal.getNativeAds().
 */
import AppodealNativeView, {
  type NativeProps,
} from './specs/AppodealNativeViewNativeComponent';

const AppodealNative = ({
  adTemplate = 'contentStream',
  placement = 'default',
  style,
  ...rest
}: NativeProps) => {
  return (
    <AppodealNativeView
      adTemplate={adTemplate}
      placement={placement}
      style={style}
      {...rest}
    />
  );
};

export default AppodealNative;
