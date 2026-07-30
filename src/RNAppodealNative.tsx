/**
 * Appodeal Native Ad Component
 *
 * Renders a platform native-ad template (newsFeed / appWall / contentStream)
 * bound to an ad id returned from Appodeal.getNativeAds().
 */
import AppodealNativeView, {
  type NativeProps,
} from './specs/AppodealNativeViewNativeComponent';

const AppodealNative = ({
  template = 'contentStream',
  placement = 'default',
  style,
  ...rest
}: NativeProps) => {
  return (
    <AppodealNativeView
      template={template}
      placement={placement}
      style={style}
      {...rest}
    />
  );
};

export default AppodealNative;
