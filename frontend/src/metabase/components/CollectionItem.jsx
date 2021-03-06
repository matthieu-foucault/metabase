import React from "react";
import { Flex } from "grid-styled";

import Card from "metabase/components/Card";
import Ellipsified from "metabase/components/Ellipsified";
import Icon from "metabase/components/Icon";
import Link from "metabase/components/Link";

import colors from "metabase/lib/colors";

const ItemLink = props => (
  <Link
    to={`collection/${props.collection.id}`}
    bg={
      props.hovered
        ? colors["brand"]
        : props.highlighted
        ? colors["bg-light"]
        : colors["bg-medium"]
    }
    color={props.hovered ? "white" : colors["text-medium"]}
    className="block rounded relative text-brand-hover"
    data-metabase-event={props.event}
    style={{
      borderSize: 1,
      borderColor: props.hovered
        ? colors["brand"]
        : props.highlighted
        ? colors["bg-medium"]
        : "transparent",
      borderStyle: props.hovered
        ? "solid"
        : props.highlighted
        ? "dotted"
        : "solid",
    }}
    hover={{ color: colors["brand"] }}
  >
    {props.children}
  </Link>
);

const ItemInfo = props => (
  <h4 className="overflow-hidden">
    <Ellipsified>{props.collection.name}</Ellipsified>
  </h4>
);

const CollectionItem = props => {
  const icon = (
    <Icon
      name={props.iconName}
      mx={props.asCard ? 0 : 1}
      color={props.asCard ? "white" : colors["bg-dark"]}
    />
  );

  const content = (
    <Flex
      align="center"
      py={props.asCard ? 1 : 2}
      px={props.asCard ? 1 : 0}
      key={`collection-${props.collection.id}`}
    >
      {props.asCard ? (
        <Flex
          align="center"
          justify="center"
          w="42px"
          bg={colors["bg-dark"]}
          style={{ height: 42, borderRadius: 6, flexShrink: 0 }}
          mr={1}
        >
          {icon}
        </Flex>
      ) : (
        icon
      )}
      <ItemInfo {...props} />
    </Flex>
  );
  return (
    <ItemLink {...props}>
      {props.asCard ? <Card hoverable>{content}</Card> : content}
    </ItemLink>
  );
};

CollectionItem.defaultProps = {
  iconName: "all",
};

export default CollectionItem;
